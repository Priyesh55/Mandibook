package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;

/**
 * A save that is retried after a lost response must not create a second bill. Real service, real PostgreSQL, the real
 * security chain with a real session of a synthetic owner (see TestcontainersConfiguration). Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BillIdempotencyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long farmer, onion;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        farmer = createMaster(mvc, "farmers", "Idem Farmer", null);
        onion = itemId(jdbc, "Onion");
    }

    private String bill(double weight) {
        return "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"villageId\":null,\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":1,\"weight\":" + weight + ",\"rate\":2,\"buyerId\":null}],\"expenses\":[]}";
    }

    private MvcResult save(String json, String key) throws Exception {
        var req = post("/api/v1/aadat/bills").contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")
                .content(json.getBytes(StandardCharsets.UTF_8));
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req).andReturn();
    }

    private static String text(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private int bills() {
        return jdbc.queryForObject("select count(*) from aadat_bill", Integer.class);
    }

    @Test
    void aRetryWithTheSameKeyAndContentReturnsTheSameBillAndWritesNothing() throws Exception {
        String k = key();
        MvcResult first = save(bill(5), k);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        long id = ((Number) JsonPath.read(text(first), "$.id")).longValue();
        int auditBefore = jdbc.queryForObject("select count(*) from audit_event where entity_type = 'AADAT_BILL' and entity_id = ?", Integer.class, id);

        MvcResult again = save(bill(5), k);
        assertThat(again.getResponse().getStatus()).as("a replay is not a creation").isEqualTo(200);
        assertThat(again.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(((Number) JsonPath.read(text(again), "$.id")).longValue()).isEqualTo(id);
        for (String path : new String[] {"$.id", "$.billDate", "$.farmer.id", "$.lots[0].weight", "$.lots[0].rate", "$.lots.length()", "$.expenses.length()"}) {
            Object a = JsonPath.read(text(again), path), b = JsonPath.read(text(first), path);
            // the database stores 6 decimals (2 comes back as 2.000000): equal as numbers
            if (a instanceof Number x && b instanceof Number y) assertThat(new java.math.BigDecimal(x.toString())).as("the same bill: " + path).isEqualByComparingTo(new java.math.BigDecimal(y.toString()));
            else assertThat(a).as("the same bill: " + path).isEqualTo(b);
        }
        assertThat(bills()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'AADAT_BILL' and entity_id = ?", Integer.class, id))
                .as("the replay wrote no second history entry").isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select count(*) from aadat_lot", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithDifferentContentIsRefusedAndNothingChanges() throws Exception {
        String k = key();
        assertThat(save(bill(5), k).getResponse().getStatus()).isEqualTo(201);
        MvcResult other = save(bill(6), k);
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(text(other)).contains("save key was already used");
        assertThat(bills()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select weight from aadat_lot", java.math.BigDecimal.class).stripTrailingZeros()).isEqualByComparingTo("5");
    }

    @Test
    void differentKeysAndRequestsWithoutAKeyAreSeparateBillsAndBadKeysAreRefused() throws Exception {
        assertThat(save(bill(5), key()).getResponse().getStatus()).isEqualTo(201);
        assertThat(save(bill(5), key()).getResponse().getStatus()).isEqualTo(201);
        assertThat(save(bill(5), null).getResponse().getStatus()).as("no key: the previous behaviour").isEqualTo(201);
        assertThat(save(bill(5), null).getResponse().getStatus()).isEqualTo(201);
        assertThat(bills()).isEqualTo(4);
        for (String bad : new String[] {"short", "has space in it", "x".repeat(65), "semi;colon-key", "ключ-ключ-ключ"}) {
            MvcResult r = save(bill(5), bad);
            assertThat(r.getResponse().getStatus()).as("key '" + bad + "'").isEqualTo(422);
        }
        assertThat(bills()).isEqualTo(4);
    }

    @Test
    void simultaneousRetriesOfOneSaveProduceExactlyOneBill() throws Exception {
        String k = key();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return save(bill(5), k);
            }));
        }
        go.countDown();
        Set<Long> ids = new HashSet<>();
        int created = 0, replayed = 0;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(r.getResponse().getStatus()).as(text(r)).isIn(200, 201);
            ids.add(((Number) JsonPath.read(text(r), "$.id")).longValue());
            if (r.getResponse().getStatus() == 201) created++; else replayed++;
        }
        pool.shutdown();
        assertThat(created).as("exactly one request created the bill").isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        assertThat(ids).as("every caller got the same bill").hasSize(1);
        assertThat(bills()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'AADAT_BILL'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theKeyBelongsToTheFirmAndTheDatabaseKeepsKeysUniquePerFirm() {
        long other = jdbc.queryForObject("insert into firm (code, name) values (?, 'Idem Other') returning id", Long.class, "I" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        long farmerOther = jdbc.queryForObject("insert into farmer (name, firm_id) values ('Other Idem Farmer', ?) returning id", Long.class, other);
        String k = "shared-key-" + UUID.randomUUID();
        byte[] hash = new byte[32];
        String sql = "insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id, idempotency_key, request_hash) values ('2026-10-01', ?, 'NOT_CALCULATED', 'NONE', ?, ?, ?)";
        long defaultFirm = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        jdbc.update(sql, farmer, defaultFirm, k, hash);
        jdbc.update(sql, farmerOther, other, k, hash);                       // the same key in another firm is another key
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, farmer, defaultFirm, k, hash))
                .as("the same key twice in one firm").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id, idempotency_key) "
                + "values ('2026-10-01', ?, 'NOT_CALCULATED', 'NONE', ?, 'a-key-without-hash')", farmer, defaultFirm))
                .as("a key needs its request fingerprint").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
