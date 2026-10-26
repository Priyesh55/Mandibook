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

import java.math.BigDecimal;
import java.util.List;
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

/** Default configuration: demonstration calculation is NOT enabled. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AadatApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long farmer, village, buyer, expenseHead, onion, potato;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        farmer = createMaster(mvc, "farmers", "Test Farmer 1", "टेस्ट किसान 1");
        village = createMaster(mvc, "villages", "Test Village", null);
        buyer = createMaster(mvc, "buyers", "Test Buyer 1", null);
        expenseHead = createMaster(mvc, "expense-heads", "Test Expense X", null);
        onion = itemId(jdbc, "Onion");
        potato = itemId(jdbc, "Potato");
    }

    private String billJson(String extra) {
        return """
            {"billDate":"2026-10-17","farmerId":%d,"villageId":%d,"vehicleNo":"TEST-1234"%s,
             "lots":[
               {"itemId":%d,"pieces":20,"weight":1000.5,"rate":1825,"buyerId":%d},
               {"itemId":%d,"pieces":5,"weight":250,"rate":1200,"buyerId":null}],
             "expenses":[{"expenseHeadId":%d,"amount":500}]}
            """.formatted(farmer, village, extra, onion, buyer, potato, expenseHead);
    }

    @Test
    void seededItemsExist() {
        List<String> names = jdbc.queryForList("select name from item order by name", String.class);
        assertThat(names).contains("Onion", "Potato", "Garlic");
    }

    @Test
    void masterSearchWorksInEnglishAndHindiAndInactiveCanBeHidden() throws Exception {
        getJson(mvc, "/api/v1/masters/farmers?q=test farmer").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Farmer 1"));
        String hindi = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/masters/farmers").param("q", "किसान")));
        assertThat((List<?>) JsonPath.read(hindi, "$")).hasSize(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/masters/farmers/" + farmer + "/active")
                .contentType("application/json").content("{\"active\":false}")).andExpect(status().isOk());
        getJson(mvc, "/api/v1/masters/farmers?activeOnly=true").andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void hindiAndEnglishValidationMessages() throws Exception {
        String hi = body(postJson(mvc, "/api/v1/masters/farmers", "{\"name\":\"\"}", "hi").andExpect(status().isBadRequest()));
        assertThat(hi).contains("नाम आवश्यक है");
        String en = body(postJson(mvc, "/api/v1/masters/farmers", "{\"name\":\"\"}", "en").andExpect(status().isBadRequest()));
        assertThat(en).contains("Name is required");
    }

    @Test
    void unknownMasterTypeIs404() throws Exception {
        getJson(mvc, "/api/v1/masters/nonsense").andExpect(status().isNotFound());
    }

    @Test
    void createBillCalculatesLotValuesAndRecordsTheCalculatedStatus() throws Exception {
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", billJson(""), "en").andExpect(status().isCreated()));
        long id = ((Number) JsonPath.read(resp, "$.id")).longValue();

        // API response
        assertThat((String) JsonPath.read(resp, "$.calculationStatus")).isEqualTo("CALCULATED");
        assertThat((String) JsonPath.read(resp, "$.ruleSetId")).isEqualTo("OWNER_2026_10_25_Q100");
        // rates are per 100 kg: 1000.5 kg at 18.25 a kg (entered 1825) = 18259.125 -> 18259; 250 kg at 12 a kg (1200) = 3000; gross 21259; expenses 500; net 20759
        assertThat(((Number) JsonPath.read(resp, "$.lots[0].lotValue")).longValue()).isEqualTo(18259L);
        assertThat(((Number) JsonPath.read(resp, "$.lots[1].lotValue")).longValue()).isEqualTo(3000L);
        assertThat((String) JsonPath.read(resp, "$.totals.basis")).isEqualTo("CALCULATED");
        assertThat(((Number) JsonPath.read(resp, "$.totals.farmerSide.grossOfEnteredLotValues")).longValue()).isEqualTo(21259L);
        assertThat(((Number) JsonPath.read(resp, "$.totals.farmerSide.netOfEnteredAmounts")).longValue()).isEqualTo(20759L);

        // Database record, verified independently of the API
        Map<String, Object> bill = jdbc.queryForMap("select * from aadat_bill where id = ?", id);
        assertThat(bill.get("bill_date").toString()).isEqualTo("2026-10-17");
        assertThat(bill.get("farmer_id")).isEqualTo(farmer);
        assertThat(bill.get("village_id")).isEqualTo(village);
        assertThat(bill.get("vehicle_no")).isEqualTo("TEST-1234");
        assertThat(bill.get("calculation_status")).isEqualTo("CALCULATED");
        assertThat(bill.get("rule_set_id")).isEqualTo("OWNER_2026_10_25_Q100");

        List<Map<String, Object>> lots = jdbc.queryForList(
                "select * from aadat_lot where bill_id = ? order by line_no", id);
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).get("item_id")).isEqualTo(onion);
        assertThat(lots.get(0).get("pieces")).isEqualTo(20);
        assertThat((BigDecimal) lots.get(0).get("weight")).isEqualByComparingTo("1000.5");
        assertThat((BigDecimal) lots.get(0).get("rate")).isEqualByComparingTo("1825");
        assertThat(lots.get(0).get("buyer_id")).isEqualTo(buyer);
        assertThat((BigDecimal) lots.get(0).get("lot_value")).isEqualByComparingTo("18259");
        assertThat((BigDecimal) lots.get(1).get("rate")).isEqualByComparingTo("1200");
        assertThat(lots.get(1).get("buyer_id")).isNull();

        List<Map<String, Object>> exps = jdbc.queryForList("select * from aadat_expense where bill_id = ?", id);
        assertThat(exps).hasSize(1);
        assertThat((BigDecimal) exps.get(0).get("amount")).isEqualByComparingTo("500");

        // Reopen
        getJson(mvc, "/api/v1/aadat/bills/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.lots.length()").value(2))
                .andExpect(jsonPath("$.lots[0].item.name").value("Onion"))
                .andExpect(jsonPath("$.lots[0].buyer.name").value("Test Buyer 1"))
                .andExpect(jsonPath("$.farmer.nameHi").value("टेस्ट किसान 1"))
                .andExpect(jsonPath("$.expenses[0].amount").value(500.0));
    }

    @Test
    void noMonetaryResultColumnsExistInStorage() {
        List<String> cols = jdbc.queryForList("""
            select column_name from information_schema.columns
            where table_name in ('aadat_bill') """, String.class);
        // bhada_amount (V11) is an amount the operator TYPES in the bill header; it is an input, not a computed result
        assertThat(cols).filteredOn(c -> !c.equals("bhada_amount")).noneMatch(c -> c.contains("total") || c.contains("amount")
                || c.contains("payable") || c.contains("receivable"));
    }

    @Test
    void demoCalculationIsRefusedWhenServerHasNotEnabledIt() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills", billJson(",\"calculationMode\":\"DEMO_UNVERIFIED\""), "en")
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
        getJson(mvc, "/api/v1/config").andExpect(jsonPath("$.demoCalculationEnabled").value(false));
    }

    @Test
    void previewWithoutDemoReturnsNoAmounts() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills/preview", billJson(""), "en").andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.mode").value("NOT_CALCULATED"))
                .andExpect(jsonPath("$.lotAmounts").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
    }

    @Test
    void validationErrorsAreLocalized() throws Exception {
        String noLots = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[]}";
        assertThat(body(postJson(mvc, "/api/v1/aadat/bills", noLots, "hi").andExpect(status().isBadRequest())))
                .contains("कम से कम एक लॉट");
        assertThat(body(postJson(mvc, "/api/v1/aadat/bills", noLots, "en").andExpect(status().isBadRequest())))
                .contains("At least one lot");
        String negative = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer
                + ",\"lots\":[{\"itemId\":" + onion + ",\"weight\":-1}]}";
        postJson(mvc, "/api/v1/aadat/bills", negative, "en").andExpect(status().isBadRequest());
    }

    @Test
    void inactiveOrMissingMasterReferencesAreRejected() throws Exception {
        String bad = "{\"billDate\":\"2026-10-17\",\"farmerId\":999999,\"lots\":[{\"itemId\":" + onion + "}]}";
        postJson(mvc, "/api/v1/aadat/bills", bad, "en").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void searchByDateFarmerAndTextAndUnknownBillIs404() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills", billJson(""), "en").andExpect(status().isCreated());

        getJson(mvc, "/api/v1/aadat/bills?from=2026-10-01&to=2026-10-31").andExpect(jsonPath("$.totalElements").value(1));
        getJson(mvc, "/api/v1/aadat/bills?from=2026-11-01").andExpect(jsonPath("$.totalElements").value(0));
        getJson(mvc, "/api/v1/aadat/bills?farmerId=" + farmer).andExpect(jsonPath("$.totalElements").value(1));
        getJson(mvc, "/api/v1/aadat/bills?q=test-12").andExpect(jsonPath("$.totalElements").value(1));
        getJson(mvc, "/api/v1/aadat/bills?q=farmer 1").andExpect(jsonPath("$.totalElements").value(1));
        getJson(mvc, "/api/v1/aadat/bills?q=zzz-none").andExpect(jsonPath("$.totalElements").value(0));

        // Search rows carry no amounts of any kind.
        String rows = body(getJson(mvc, "/api/v1/aadat/bills"));
        Map<String, Object> firstRow = JsonPath.read(rows, "$.content[0]");
        assertThat(firstRow).doesNotContainKeys("total", "amount", "lotSubtotal", "payable", "receivable");
        assertThat((Integer) JsonPath.read(rows, "$.content[0].lotCount")).isEqualTo(2);

        getJson(mvc, "/api/v1/aadat/bills/987654321").andExpect(status().isNotFound());
    }

    @Test
    void nullListElementsAreRejectedNotCrashed() throws Exception {
        String json = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[null]}";
        postJson(mvc, "/api/v1/aadat/bills", json, "en").andExpect(status().isBadRequest());
        String json2 = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + "}],\"expenses\":[null]}";
        postJson(mvc, "/api/v1/aadat/bills", json2, "en").andExpect(status().isBadRequest());
    }

    @Test
    void malformedRequestsGetLocalizedErrorsNotFrameworkDefaults() throws Exception {
        String badPieces = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":2.5}]}";
        assertThat(body(postJson(mvc, "/api/v1/aadat/bills/preview", badPieces, "hi").andExpect(status().isBadRequest())))
                .contains("अनुरोध गलत है");
        assertThat(body(postJson(mvc, "/api/v1/aadat/bills", "{not json", "en").andExpect(status().isBadRequest())))
                .contains("malformed");
        String badMode = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"calculationMode\":\"NOPE\",\"lots\":[{\"itemId\":"
                + onion + "}]}";
        postJson(mvc, "/api/v1/aadat/bills", badMode, "en").andExpect(status().isBadRequest());
        getJson(mvc, "/api/v1/aadat/bills/abc").andExpect(status().isBadRequest());
        getJson(mvc, "/api/v1/no-such-endpoint").andExpect(status().isNotFound());
    }

    @Test
    void numbersBeyondTheSupportedPrecisionAreRejectedNotSilentlyChanged() throws Exception {
        String tooMany = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"weight\":123456789012.123456}]}";
        postJson(mvc, "/api/v1/aadat/bills", tooMany, "en").andExpect(status().isBadRequest());
        String ok = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"weight\":123456789.1234,\"rate\":1}]}";
        postJson(mvc, "/api/v1/aadat/bills", ok, "en").andExpect(status().isCreated());
    }

    @Test
    void searchWildcardsAreTreatedLiterally() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills", billJson(""), "en").andExpect(status().isCreated());
        getJson(mvc, "/api/v1/aadat/bills?q=%25").andExpect(jsonPath("$.totalElements").value(0));
        getJson(mvc, "/api/v1/aadat/bills?q=_").andExpect(jsonPath("$.totalElements").value(0));
        getJson(mvc, "/api/v1/masters/farmers?q=%25").andExpect(jsonPath("$").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/masters/farmers").param("q", "Test Farmer")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void inactiveMasterCannotBeUsedOnANewBill() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/masters/buyers/" + buyer + "/active")
                .contentType("application/json").content("{\"active\":false}")).andExpect(status().isOk());
        postJson(mvc, "/api/v1/aadat/bills", billJson(""), "en").andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
    }

    @Test
    void paginationBoundsAreClamped() throws Exception {
        postJson(mvc, "/api/v1/aadat/bills", billJson(""), "en").andExpect(status().isCreated());
        getJson(mvc, "/api/v1/aadat/bills?size=100000&page=-5").andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.page").value(0));
        getJson(mvc, "/api/v1/aadat/bills?size=0").andExpect(jsonPath("$.size").value(1));
    }
}
