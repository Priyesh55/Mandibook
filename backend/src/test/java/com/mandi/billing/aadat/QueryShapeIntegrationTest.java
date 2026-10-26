package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;

import jakarta.persistence.EntityManagerFactory;

/**
 * Query shapes (statement counts) and master paging. Hibernate statistics are on for this context, and the master
 * list safety cap is set to 3 so the cap can be tested. Synthetic data only.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "mandi.masters.max-list-size=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QueryShapeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired AadatBillRepository bills;
    @Autowired PlatformTransactionManager tm;
    @Autowired Environment env;

    Statistics stats;
    long farmer, village, onion, potato, garlic, buyer1, buyer2, head1, head2;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        farmer = createMaster(mvc, "farmers", "Shape Farmer", null);
        village = createMaster(mvc, "villages", "Shape Village", null);
        buyer1 = createMaster(mvc, "buyers", "Shape Buyer 1", null);
        buyer2 = createMaster(mvc, "buyers", "Shape Buyer 2", null);
        head1 = createMaster(mvc, "expense-heads", "Shape Head 1", null);
        head2 = createMaster(mvc, "expense-heads", "Shape Head 2", null);
        onion = itemId(jdbc, "Onion");
        potato = itemId(jdbc, "Potato");
        garlic = itemId(jdbc, "Garlic");
    }

    private long createBill() throws Exception {
        String json = """
            {"billDate":"2026-10-17","farmerId":%d,"villageId":%d,"vehicleNo":"SHAPE-1",
             "lots":[
               {"itemId":%d,"pieces":20,"weight":1000.5,"rate":18.25,"buyerId":%d},
               {"itemId":%d,"pieces":5,"weight":250,"rate":12,"buyerId":%d},
               {"itemId":%d,"pieces":1,"weight":10,"rate":3,"buyerId":null}],
             "expenses":[{"expenseHeadId":%d,"amount":500},{"expenseHeadId":%d,"amount":25}]}
            """.formatted(farmer, village, onion, buyer1, potato, buyer2, garlic, head1, head2);
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", json, "en").andExpect(status().isCreated()));
        return ((Number) JsonPath.read(resp, "$.id")).longValue();
    }

    // ---------------------------------------------------------------- bill detail: no N+1

    @Test
    void billDetailUsesTwoStatementsNoMatterHowManyLotsAndExpenses() throws Exception {
        long id = createBill();
        long firmId = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);

        // The former loading path, reproduced: lazy lots, items, buyers, expenses and heads one by one.
        stats.clear();
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            AadatBill b = bills.findByIdAndFirmId(id, firmId).orElseThrow();
            b.getFarmer().getName();
            if (b.getVillage() != null) b.getVillage().getName();
            b.getLots().forEach(l -> {
                l.getItem().getName();
                if (l.getBuyer() != null) l.getBuyer().getName();
            });
            b.getExpenses().forEach(e -> e.getExpenseHead().getName());
        });
        long before = stats.getPrepareStatementCount();

        stats.clear();
        getJson(mvc, "/api/v1/aadat/bills/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.lots.length()").value(3))
                .andExpect(jsonPath("$.lots[0].item.name").value("Onion"))
                .andExpect(jsonPath("$.lots[0].buyer.name").value("Shape Buyer 1"))
                .andExpect(jsonPath("$.lots[1].item.name").value("Potato"))
                .andExpect(jsonPath("$.lots[1].buyer.name").value("Shape Buyer 2"))
                .andExpect(jsonPath("$.lots[2].item.name").value("Garlic"))
                .andExpect(jsonPath("$.lots[2].buyer").doesNotExist())
                .andExpect(jsonPath("$.expenses.length()").value(2))
                .andExpect(jsonPath("$.expenses[0].expenseHead.name").value("Shape Head 1"))
                .andExpect(jsonPath("$.expenses[1].expenseHead.name").value("Shape Head 2"))
                .andExpect(jsonPath("$.expenses[1].amount").value(25.0))
                .andExpect(jsonPath("$.farmer.name").value("Shape Farmer"))
                .andExpect(jsonPath("$.village.name").value("Shape Village"));
        long after = stats.getPrepareStatementCount();

        System.out.println("QUERYCOUNT bill detail (3 lots, 2 expenses): before=" + before + " after=" + after);
        // bill + lots, expenses, the purchaser rows of all lots in one batch, and the firm print profile: constant, whatever the lot count
        assertThat(after).as("statements to read one bill").isEqualTo(4);
        assertThat(before).as("the old path needed far more statements").isGreaterThan(after);
    }

    @Test
    void unknownBillIsStillNotFound() throws Exception {
        getJson(mvc, "/api/v1/aadat/bills/999999999").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- bill list: lot counts without loading lots

    @Test
    void billListCountsLotsInOneGroupedQueryAndLoadsNoLotRows() throws Exception {
        for (int i = 0; i < 4; i++) createBill();
        stats.clear();
        getJson(mvc, "/api/v1/aadat/bills?size=20").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].lotCount").value(3))
                .andExpect(jsonPath("$.content[3].lotCount").value(3))
                .andExpect(jsonPath("$.content[0].farmer.name").value("Shape Farmer"))
                .andExpect(jsonPath("$.content[0].village.name").value("Shape Village"));
        long statements = stats.getPrepareStatementCount();
        long lotRowsLoaded = stats.getEntityStatistics(AadatLot.class.getName()).getLoadCount();
        System.out.println("QUERYCOUNT bill list (4 bills): statements=" + statements + " lotEntitiesLoaded=" + lotRowsLoaded);
        assertThat(lotRowsLoaded).as("lots are not loaded just to be counted").isZero();
        // the page is not full, so the total is derived from the rows and no separate count statement runs; the farmer-side figures
        // of the whole page come from two grouped sums (never one query per bill)
        assertThat(statements).as("page + lot counts + gross sums + expense sums").isEqualTo(4);

        // a full page needs the total as well: page + total + lot counts
        stats.clear();
        getJson(mvc, "/api/v1/aadat/bills?size=2").andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.content[1].lotCount").value(3));
        System.out.println("QUERYCOUNT bill list (full page of 2 of 4): statements=" + stats.getPrepareStatementCount());
        assertThat(stats.getPrepareStatementCount()).isEqualTo(5);
        assertThat(stats.getEntityStatistics(AadatLot.class.getName()).getLoadCount()).isZero();
    }

    @Test
    void anEmptyBillListRunsNoLotCountQuery() throws Exception {
        stats.clear();
        getJson(mvc, "/api/v1/aadat/bills?size=20").andExpect(jsonPath("$.content.length()").value(0));
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------- master paging

    private void createFarmers(String... names) throws Exception {
        for (String n : names) createMaster(mvc, "farmers", n, null);
    }

    @Test
    void pagesAreStableCoverEveryRowOnceAndFollowNameThenId() throws Exception {
        createFarmers("Pg 3", "Pg 1", "Pg Same", "Pg 2", "Pg Same", "Pg 5", "Pg 4", "Pg Same", "Pg 6");
        long expectedTotal = 9 + 1; // plus "Shape Farmer" from setUp
        List<Long> seen = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            String resp = body(getJson(mvc, "/api/v1/masters/farmers/page?page=" + p + "&size=3").andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(expectedTotal))
                    .andExpect(jsonPath("$.page").value(p)).andExpect(jsonPath("$.size").value(3)));
            List<Number> ids = JsonPath.read(resp, "$.content[*].id");
            List<String> pageNames = JsonPath.read(resp, "$.content[*].name");
            ids.forEach(i -> seen.add(i.longValue()));
            names.addAll(pageNames);
        }
        assertThat(seen).as("every row appears exactly once across pages").hasSize((int) expectedTotal).doesNotHaveDuplicates();
        assertThat(names).isSortedAccordingTo(Comparator.naturalOrder());
        // equal names are ordered by id, so the three "Pg Same" rows keep a fixed order
        List<Long> sameIds = jdbc.queryForList("select id from farmer where name = 'Pg Same' order by id", Long.class);
        List<Long> sameSeen = seen.stream().filter(sameIds::contains).toList();
        assertThat(sameSeen).isEqualTo(sameIds);
        // past the end: empty, not an error
        getJson(mvc, "/api/v1/masters/farmers/page?page=50&size=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0)).andExpect(jsonPath("$.totalElements").value(expectedTotal));
    }

    @Test
    void pageSizeAndPageAreClampedToSafeValues() throws Exception {
        createFarmers("Clamp 1");
        getJson(mvc, "/api/v1/masters/farmers/page?size=100000").andExpect(jsonPath("$.size").value(200));
        getJson(mvc, "/api/v1/masters/farmers/page?size=0").andExpect(jsonPath("$.size").value(1));
        getJson(mvc, "/api/v1/masters/farmers/page?size=-7").andExpect(jsonPath("$.size").value(1));
        getJson(mvc, "/api/v1/masters/farmers/page?page=-5").andExpect(jsonPath("$.page").value(0));
        getJson(mvc, "/api/v1/masters/farmers/page").andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void pageFiltersBySearchAndActiveAndCountsOnlyMatches() throws Exception {
        createFarmers("Filt Alpha", "Filt Beta", "Other Gamma");
        long beta = jdbc.queryForObject("select id from farmer where name = 'Filt Beta'", Long.class);
        getJson(mvc, "/api/v1/masters/farmers/page?q=filt").andExpect(jsonPath("$.totalElements").value(2));
        // a LIKE wildcard typed by the user is literal text, not a pattern (sent as a real parameter, not in the URL)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/masters/farmers/page").param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/masters/farmers/" + beta + "/active")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        getJson(mvc, "/api/v1/masters/farmers/page?q=filt&activeOnly=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Filt Alpha"));
        getJson(mvc, "/api/v1/masters/nonsense/page").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- existing list endpoint: same shape, now bounded

    @Test
    void theExistingListStaysAJsonArrayAndIsCappedWithAnExplicitSignal() throws Exception {
        createFarmers("Cap A", "Cap B", "Cap C", "Cap D"); // plus "Shape Farmer" = 5 rows, cap is 3
        getJson(mvc, "/api/v1/masters/farmers").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("X-Result-Truncated", "true"))
                .andExpect(jsonPath("$[0].name").value("Cap A"));
        // a narrower request fits under the cap: no signal
        getJson(mvc, "/api/v1/masters/farmers?q=cap a").andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().doesNotExist("X-Result-Truncated"));
        // exactly at the cap is not truncated
        jdbc.update("update farmer set active = false where name = 'Cap D'");
        getJson(mvc, "/api/v1/masters/farmers?q=cap&activeOnly=true").andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().doesNotExist("X-Result-Truncated"));
    }

    /**
     * V5 uses CREATE INDEX CONCURRENTLY. With Flyway's default transactional lock the migration hangs forever (its
     * first connection keeps a transaction open that the index build waits for), so the setting must stay off.
     */
    @Test
    void flywayHoldsItsMigrationLockWithoutAnOpenTransaction() {
        assertThat(env.getProperty("spring.flyway.postgresql.transactional-lock")).isEqualTo("false");
    }
}
