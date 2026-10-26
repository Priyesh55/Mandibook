package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;

/** Opt-in demonstration mode: results stay flagged unverified and never reach storage or search rows. */
@SpringBootTest(properties = "mandi.calculation.demo-enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoModeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long farmer, onion;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        farmer = createMaster(mvc, "farmers", "Demo Test Farmer", null);
        onion = itemId(jdbc, "Onion");
    }

    private String json(String mode) {
        return """
            {"billDate":"2026-10-17","farmerId":%d%s,
             "lots":[{"itemId":%d,"pieces":10,"weight":100,"rate":2.5}],
             "expenses":[{"expenseHeadId":%d,"amount":7.25}]}
            """.formatted(farmer, mode, onion, createExpenseHead());
    }

    private long createExpenseHead() {
        try {
            return createMaster(mvc, "expense-heads", "Demo Test Expense", null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void demoIsAvailableButNeverTheDefault() throws Exception {
        getJson(mvc, "/api/v1/config").andExpect(jsonPath("$.demoCalculationEnabled").value(true));
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", json(""), "en").andExpect(status().isCreated()));
        // the default is the owner-confirmed calculation, never the unverified demonstration
        assertThat((String) JsonPath.read(resp, "$.calculationStatus")).isEqualTo("CALCULATED");
        assertThat((Boolean) JsonPath.read(resp, "$.calculation.verified")).isTrue();
    }

    @Test
    void explicitDemoRequestIsFlaggedUnverifiedAndRecordedOnTheBill() throws Exception {
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", json(",\"calculationMode\":\"DEMO_UNVERIFIED\""), "en")
                .andExpect(status().isCreated()));
        long id = ((Number) JsonPath.read(resp, "$.id")).longValue();

        assertThat((Boolean) JsonPath.read(resp, "$.calculation.verified")).isFalse();
        assertThat((String) JsonPath.read(resp, "$.calculation.noticeKey")).isEqualTo("calculation.demoUnverified");
        assertThat(((Number) JsonPath.read(resp, "$.calculation.lotAmounts[0].amount")).doubleValue()).isEqualTo(250.0);

        // Status and rule set are preserved on the stored bill; the figures themselves are not stored.
        Map<String, Object> row = jdbc.queryForMap("select * from aadat_bill where id = ?", id);
        assertThat(row.get("calculation_status")).isEqualTo("DEMO_UNVERIFIED");
        assertThat(row.get("rule_set_id")).isEqualTo("DEMO_WEIGHT_X_RATE_V0");
        assertThat(row.keySet()).filteredOn(c -> !c.equals("bhada_amount")).noneMatch(c -> c.contains("total") || c.contains("amount")); // bhada_amount is typed input

        // Reopening recomputes with the same unverified flag.
        getJson(mvc, "/api/v1/aadat/bills/" + id)
                .andExpect(jsonPath("$.calculation.verified").value(false))
                .andExpect(jsonPath("$.calculationStatus").value("DEMO_UNVERIFIED"));

        // Demonstration bills are hidden from listings unless explicitly requested.
        getJson(mvc, "/api/v1/aadat/bills").andExpect(jsonPath("$.totalElements").value(0));

        // When requested, rows expose the status but no amounts.
        String rows = body(getJson(mvc, "/api/v1/aadat/bills?includeDemo=true"));
        assertThat((String) JsonPath.read(rows, "$.content[0].calculationStatus")).isEqualTo("DEMO_UNVERIFIED");
        Map<String, Object> firstRow = JsonPath.read(rows, "$.content[0]");
        assertThat(firstRow).doesNotContainKeys("total", "amount", "lotSubtotal");
    }

    @Test
    void previewWithDemoIsFlaggedUnverifiedAndPersistsNothing() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills/preview", json(",\"calculationMode\":\"DEMO_UNVERIFIED\""), "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.ruleSetId").value("DEMO_WEIGHT_X_RATE_V0"));
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
    }
}
