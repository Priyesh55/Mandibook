package com.mandi.firm;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;

/**
 * REPOSITORY-LEVEL isolation of every master and bill path between two firms. A switchable CurrentFirm replaces the real
 * request-bound one so that one session can act as either firm and the service and repository code is exercised for both;
 * the real authenticated, membership-checked requests of two owners and employees are tested over HTTP in
 * com.mandi.security.FirmContextIsolationIntegrationTest. Together they cover the two layers. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FirmIsolationIntegrationTest.SwitchableFirmConfig.class})
class FirmIsolationIntegrationTest {

    /** Test stand-in for the authenticated user's firm. */
    static class SwitchableFirm implements CurrentFirm {
        private volatile long id;

        SwitchableFirm(long id) { this.id = id; }

        void use(long firmId) { this.id = firmId; }

        @Override public long id() { return id; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableFirmConfig {
        @Bean
        @Primary
        SwitchableFirm switchableFirm(JdbcTemplate jdbc) {
            return new SwitchableFirm(jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwitchableFirm firm;

    long a, b;

    @BeforeEach
    void setUp() {
        clean(jdbc); // audit_event is intentionally not cleaned: it cannot be deleted
        a = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        b = jdbc.queryForObject("insert into firm (code, name) values (?, 'Firm B') returning id", Long.class,
                "B-" + UUID.randomUUID().toString().substring(0, 8));
        firm.use(a);
    }

    @AfterEach
    void backToDefault() {
        firm.use(a);
    }

    private String billJson(long farmer, Long village, long item, Long buyer, Long head) {
        return "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"villageId\":" + village
                + ",\"lots\":[{\"itemId\":" + item + ",\"pieces\":1,\"weight\":5,\"rate\":2,\"buyerId\":" + buyer + "}],"
                + "\"expenses\":[" + (head == null ? "" : "{\"expenseHeadId\":" + head + ",\"amount\":3}") + "]}";
    }

    private long createBill(String json) throws Exception {
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", json, "en").andExpect(status().isCreated()));
        return ((Number) JsonPath.read(resp, "$.id")).longValue();
    }

    private int billsOf(long firmId) {
        return jdbc.queryForObject("select count(*) from aadat_bill where firm_id = ?", Integer.class, firmId);
    }

    @Test
    void aFirmCannotSeeChangeOrListAnotherFirmsRecords() throws Exception {
        // Firm A creates its records and a bill.
        long farmerA = createMaster(mvc, "farmers", "Iso Farmer A", null);
        long villageA = createMaster(mvc, "villages", "Iso Village A", null);
        long buyerA = createMaster(mvc, "buyers", "Iso Buyer A", null);
        long headA = createMaster(mvc, "expense-heads", "Iso Head A", null);
        long onionA = itemId(jdbc, "Onion");
        long billA = createBill(billJson(farmerA, villageA, onionA, buyerA, headA));

        // Firm B cannot read, list, search, edit, deactivate or find the history of firm A's records.
        firm.use(b);
        getJson(mvc, "/api/v1/aadat/bills/" + billA).andExpect(status().isNotFound());
        getJson(mvc, "/api/v1/aadat/bills?includeDemo=true").andExpect(jsonPath("$.totalElements").value(0));
        getJson(mvc, "/api/v1/aadat/bills?q=Iso&includeDemo=true").andExpect(jsonPath("$.totalElements").value(0));
        for (String type : new String[] {"farmers", "villages", "buyers", "expense-heads"}) {
            getJson(mvc, "/api/v1/masters/" + type).andExpect(jsonPath("$.length()").value(0));
            // the paged endpoint is firm-scoped too (total as well as rows)
            getJson(mvc, "/api/v1/masters/" + type + "/page").andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
        getJson(mvc, "/api/v1/masters/farmers?q=Iso").andExpect(jsonPath("$.length()").value(0));
        getJson(mvc, "/api/v1/masters/farmers/page?q=Iso").andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(put("/api/v1/masters/farmers/" + farmerA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\"}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/masters/farmers/" + farmerA + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isNotFound());
        getJson(mvc, "/api/v1/audit?entityType=AADAT_BILL&entityId=" + billA).andExpect(jsonPath("$.length()").value(0));
        getJson(mvc, "/api/v1/audit?entityType=FARMER&entityId=" + farmerA).andExpect(jsonPath("$.length()").value(0));

        // Nothing of firm A was altered by those attempts.
        assertThat(jdbc.queryForObject("select name from farmer where id = ?", String.class, farmerA)).isEqualTo("Iso Farmer A");
        assertThat(jdbc.queryForObject("select active from farmer where id = ?", Boolean.class, farmerA)).isTrue();

        // Firm B has its own records and bill; firm A cannot see them either.
        long farmerB = createMaster(mvc, "farmers", "Iso Farmer B", null);
        long itemB = createMaster(mvc, "items", "Iso Item B", null);
        long billB = createBill(billJson(farmerB, null, itemB, null, null));
        firm.use(a);
        getJson(mvc, "/api/v1/aadat/bills/" + billB).andExpect(status().isNotFound());
        getJson(mvc, "/api/v1/aadat/bills?includeDemo=true").andExpect(jsonPath("$.totalElements").value(1));
        getJson(mvc, "/api/v1/masters/farmers").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Iso Farmer A"));
        getJson(mvc, "/api/v1/audit?entityType=AADAT_BILL&entityId=" + billB).andExpect(jsonPath("$.length()").value(0));

        // Each firm's history rows are stored under that firm.
        assertThat(jdbc.queryForObject("select firm_id from audit_event where entity_type = 'AADAT_BILL' and entity_id = ?", Long.class, billA)).isEqualTo(a);
        assertThat(jdbc.queryForObject("select firm_id from audit_event where entity_type = 'AADAT_BILL' and entity_id = ?", Long.class, billB)).isEqualTo(b);
        assertThat(jdbc.queryForObject("select firm_id from aadat_lot where bill_id = ?", Long.class, billA)).isEqualTo(a);
        assertThat(jdbc.queryForObject("select firm_id from aadat_expense where bill_id = ?", Long.class, billA)).isEqualTo(a);
    }

    @Test
    void idsInTheRequestBodyCannotPullInAnotherFirmsRecords() throws Exception {
        long farmerA = createMaster(mvc, "farmers", "Body Farmer A", null);
        long villageA = createMaster(mvc, "villages", "Body Village A", null);
        long buyerA = createMaster(mvc, "buyers", "Body Buyer A", null);
        long headA = createMaster(mvc, "expense-heads", "Body Head A", null);
        long onionA = itemId(jdbc, "Onion");

        firm.use(b);
        long farmerB = createMaster(mvc, "farmers", "Body Farmer B", null);
        long itemB = createMaster(mvc, "items", "Body Item B", null);

        // Every id of firm A, offered to firm B, is "not found" (422), and nothing is stored.
        String[] attacks = {
            billJson(farmerA, null, itemB, null, null),   // farmer of A
            billJson(farmerB, villageA, itemB, null, null), // village of A
            billJson(farmerB, null, onionA, null, null),   // item of A
            billJson(farmerB, null, itemB, buyerA, null),  // buyer of A
            billJson(farmerB, null, itemB, null, headA),   // expense head of A
        };
        for (String json : attacks) {
            postJson(mvc, "/api/v1/aadat/bills", json, "en").andExpect(status().isUnprocessableEntity());
            postJson(mvc, "/api/v1/aadat/bills/preview", json, "en"); // preview never stores anything
        }
        assertThat(billsOf(b)).isZero();
        assertThat(billsOf(a)).isZero();
        // The control: the same request built only from firm B's own records works.
        postJson(mvc, "/api/v1/aadat/bills", billJson(farmerB, null, itemB, null, null), "en").andExpect(status().isCreated());
        assertThat(billsOf(b)).isEqualTo(1);
    }

    @Test
    void theRequestCannotChooseTheFirmByHeaderParameterOrBodyField() throws Exception {
        // The current firm is A. The request tries every channel to make it B.
        String resp = body(mvc.perform(post("/api/v1/masters/farmers?firmId=" + b + "&firm=" + b)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Firm-Id", String.valueOf(b)).header("X-Firm", "B").header("Firm-Id", String.valueOf(b))
                .header("Accept-Language", "en")
                .content(("{\"name\":\"Sneaky Farmer\",\"firmId\":" + b + ",\"firm_id\":" + b + "}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated()));
        long id = ((Number) JsonPath.read(resp, "$.id")).longValue();
        assertThat(jdbc.queryForObject("select firm_id from farmer where id = ?", Long.class, id))
                .as("stored under the server-side current firm, not the one the request asked for").isEqualTo(a);

        // Same for reading: asking for firm B's view while acting as A changes nothing.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/masters/farmers?firmId=" + b).header("X-Firm-Id", String.valueOf(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Sneaky Farmer"));
        firm.use(b);
        getJson(mvc, "/api/v1/masters/farmers").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void thereIsNoAmbientFirmOutsideAVerifiedRequest() {
        // the real CurrentFirm has no default: outside a request that passed the membership check it FAILS
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RequestFirmContext().id())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No verified firm context");
    }
}
