package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createExpenseHeadWithRate;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
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
 * The bill workflow of V11 over the real HTTP stack and PostgreSQL: header fields, purchaser allocations typed by the
 * operator, entered-amount totals, financial year, one continuous bill number series per firm, and the server-side rules.
 * Synthetic data only; every expected figure is computed by hand here, not by the code under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BillWorkflowApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long farmer, village, buyer1, buyer2, onion, potato;
    long[] heads = new long[5];

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        farmer = createMaster(mvc, "farmers", "Wf Farmer", null);
        village = createMaster(mvc, "villages", "Wf Village", null);
        buyer1 = createMaster(mvc, "buyers", "Wf Buyer One", null);
        buyer2 = createMaster(mvc, "buyers", "Wf Buyer Two", null);
        heads[0] = createExpenseHeadWithRate(mvc, "Wf Head 1", "2.5");    // synthetic per-piece rate, configured by this test as master data
        heads[1] = createExpenseHeadWithRate(mvc, "Wf Head 2", "1.75");   // synthetic per-piece rate, configured by this test as master data
        for (int i = 2; i < heads.length; i++) heads[i] = createMaster(mvc, "expense-heads", "Wf Head " + (i + 1), null);
        onion = itemId(jdbc, "Onion");
        potato = itemId(jdbc, "Potato");
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

    private static BigDecimal dec(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
    }

    private String simple(String date, String extraHeader) {
        return "{\"billDate\":\"" + date + "\"" + extraHeader + ",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":3,\"weight\":120,\"rate\":7,\"purchasers\":[]}],\"expenses\":[]}";
    }

    /** Two lots (35 pieces), one split between two purchasers (NOT equally), two rate-based expenses and three typed ones. */
    private String full() {
        return """
            {"billDate":"2026-10-24","financialYear":2026,"farmerId":%d,"villageId":%d,"vehicleNo":"WF-1","transport":"Synthetic Carrier",
             "bhadaPaid":true,"bhadaAmount":1000,"billType":"CREDIT","panjiyanNo":"P-9","bookNo":"B-1","receiptNo":null,"entryNo":"E-3","wsNo":null,
             "lots":[
               {"itemId":%d,"pieces":20,"weight":1000,"rate":1800,"marka":"M1","purchasers":[
                  {"buyerId":%d,"haste":"Synthetic Person","pieces":12,"weight":600,"rate":1800},
                  {"buyerId":%d,"pieces":8,"weight":400,"rate":1800}]},
               {"itemId":%d,"pieces":15,"weight":1500,"rate":1500,"purchasers":[]}],
             "expenses":[{"expenseHeadId":%d,"amount":9999},{"expenseHeadId":%d},{"expenseHeadId":%d,"amount":100},
                         {"expenseHeadId":%d,"amount":75.50},{"expenseHeadId":%d,"amount":1000}]}
            """.formatted(farmer, village, onion, buyer1, buyer2, potato, heads[0], heads[1], heads[2], heads[3], heads[4]);
    }

    private int nextExpected() throws Exception {
        return ((Number) JsonPath.read(body(getJson(mvc, "/api/v1/aadat/bills/next-number")), "$.expectedNext")).intValue();
    }

    @Test
    void aBillIsSavedWithCalculatedAmountsPurchasersHeaderAndFarmerAndBuyerTotals() throws Exception {
        MvcResult r = save(full(), null);
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(201);
        String b = text(r);
        assertThat((Integer) JsonPath.read(b, "$.billNo")).isEqualTo(1);
        assertThat((String) JsonPath.read(b, "$.financialYearLabel")).isEqualTo("2026-27");
        assertThat((String) JsonPath.read(b, "$.transport")).isEqualTo("Synthetic Carrier");
        assertThat((Boolean) JsonPath.read(b, "$.bhadaPaid")).isTrue();
        assertThat(dec(b, "$.bhadaAmount")).isEqualByComparingTo("1000");
        assertThat((String) JsonPath.read(b, "$.billType")).isEqualTo("CREDIT");
        assertThat((String) JsonPath.read(b, "$.panjiyanNo")).isEqualTo("P-9");
        assertThat((Object) JsonPath.read(b, "$.receiptNo")).as("an optional number left empty stays empty").isNull();
        assertThat((String) JsonPath.read(b, "$.lots[0].marka")).isEqualTo("M1");
        assertThat(dec(b, "$.lots[0].lotValue")).as("1000 kg x 18").isEqualByComparingTo("18000");
        assertThat(dec(b, "$.lots[1].lotValue")).as("1500 kg x 15").isEqualByComparingTo("22500");

        // purchasers: weight and rate as entered, in the order entered; value and commission calculated; nothing shared out equally
        assertThat((String) JsonPath.read(b, "$.lots[0].purchasers[0].buyer.name")).isEqualTo("Wf Buyer One");
        assertThat((String) JsonPath.read(b, "$.lots[0].purchasers[0].haste")).isEqualTo("Synthetic Person");
        assertThat((Integer) JsonPath.read(b, "$.lots[0].purchasers[0].pieces")).isEqualTo(12);
        assertThat((Integer) JsonPath.read(b, "$.lots[0].purchasers[1].pieces")).isEqualTo(8);
        assertThat(dec(b, "$.lots[0].purchasers[0].weight")).isEqualByComparingTo("600");
        assertThat(dec(b, "$.lots[0].purchasers[1].weight")).isEqualByComparingTo("400");
        assertThat(dec(b, "$.lots[0].purchasers[0].value")).as("600 kg x 18").isEqualByComparingTo("10800");
        assertThat(dec(b, "$.lots[0].purchasers[1].value")).as("400 kg x 18").isEqualByComparingTo("7200");
        assertThat((Object) JsonPath.read(b, "$.lots[0].purchasers[1].haste")).isNull();
        assertThat((Boolean) JsonPath.read(b, "$.lots[0].purchasers[0].commissionApplies")).isTrue();
        assertThat(dec(b, "$.lots[0].purchasers[0].commissionPercent")).isEqualByComparingTo("5");
        assertThat(dec(b, "$.lots[0].purchasers[0].commissionAmount")).as("5 percent of 10800").isEqualByComparingTo("540");
        assertThat(dec(b, "$.lots[0].purchasers[1].commissionAmount")).as("5 percent of 7200").isEqualByComparingTo("360");
        assertThat(dec(b, "$.lots[0].purchasers[0].enteredPurchaseValue")).as("10800 + 540").isEqualByComparingTo("11340");
        assertThat(dec(b, "$.lots[0].purchasers[1].enteredPurchaseValue")).isEqualByComparingTo("7560");
        assertThat((Integer) JsonPath.read(b, "$.lots[0].pieces")).isEqualTo(20);
        assertThat(((Number) JsonPath.read(b, "$.lots[0].allocation.pieces")).longValue()).isEqualTo(20);
        assertThat(dec(b, "$.lots[0].allocation.weight")).isEqualByComparingTo("1000");
        assertThat((Integer) JsonPath.read(b, "$.lots[1].allocation.purchaserCount")).isZero();

        // expenses: 35 pieces x 2.5 = 87.50 (the typed 9999 is ignored), 35 x 1.75 = 61.25, then typed 100 + 75.50 + 1000
        assertThat(dec(b, "$.expenses[0].amount")).isEqualByComparingTo("87.5");
        assertThat(dec(b, "$.expenses[0].quantity")).isEqualByComparingTo("35");
        assertThat(dec(b, "$.expenses[0].rate")).isEqualByComparingTo("2.5");
        assertThat(dec(b, "$.expenses[1].amount")).isEqualByComparingTo("61.25");
        assertThat((Object) JsonPath.read(b, "$.expenses[2].quantity")).as("a typed expense has no quantity or rate").isNull();
        // totals, worked out by hand: gross 18000 + 22500 = 40500; expenses 87.5 + 61.25 + 100 + 75.50 + 1000 = 1324.25;
        // net 40500 - 1324.25 = 39175.75 -> 39176 (whole rupee)
        assertThat((String) JsonPath.read(b, "$.totals.basis")).isEqualTo("CALCULATED");
        assertThat(dec(b, "$.totals.farmerSide.grossOfEnteredLotValues")).isEqualByComparingTo("40500");
        assertThat(dec(b, "$.totals.farmerSide.totalOfEnteredExpenses")).isEqualByComparingTo("1324.25");
        assertThat(dec(b, "$.totals.farmerSide.netOfEnteredAmounts")).isEqualByComparingTo("39176");
        assertThat((Integer) JsonPath.read(b, "$.totals.farmerSide.lotsWithoutEnteredValue")).isZero();
        // buyer side is its own block: 10800 + 7200 = 18000; commission 540 + 360 = 900; sum 18900; never added to the farmer side
        assertThat(dec(b, "$.totals.buyerSide.enteredValue")).isEqualByComparingTo("18000");
        assertThat(dec(b, "$.totals.buyerSide.enteredCommission")).isEqualByComparingTo("900");
        assertThat(dec(b, "$.totals.buyerSide.enteredPurchaseValue")).isEqualByComparingTo("18900");
        assertThat((String) JsonPath.read(b, "$.calculationStatus")).isEqualTo("CALCULATED");
        // the bill list carries the farmer-side figures
        String list = body(getJson(mvc, "/api/v1/aadat/bills"));
        assertThat(dec(list, "$.content[0].gross")).isEqualByComparingTo("40500");
        assertThat(dec(list, "$.content[0].expenses")).isEqualByComparingTo("1324.25");
        assertThat(dec(list, "$.content[0].net")).isEqualByComparingTo("39176");

        // reading it back gives the same figures
        long id = ((Number) JsonPath.read(b, "$.id")).longValue();
        String again = body(getJson(mvc, "/api/v1/aadat/bills/" + id));
        assertThat(dec(again, "$.totals.farmerSide.netOfEnteredAmounts")).isEqualByComparingTo("39176");
        assertThat((Integer) JsonPath.read(again, "$.lots[0].purchasers.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(again, "$.billNo")).isEqualTo(1);
        // the history names the bill number
        assertThat(jdbc.queryForObject("select details from audit_event where entity_type = 'AADAT_BILL' and entity_id = ?", String.class, id))
                .contains("billNo=1").contains("fy=2026-27").contains("purchaserRows=2");
    }

    @Test
    void anExpenseHeadRateNeedsItsBasisAndIsOnlyForExpenseHeads() throws Exception {
        String head = body(getJson(mvc, "/api/v1/masters/expense-heads"));
        assertThat(new BigDecimal(((List<?>) JsonPath.read(head, "$[?(@.id==" + heads[0] + ")].rate")).get(0).toString())).isEqualByComparingTo("2.5");
        assertThat(((List<?>) JsonPath.read(head, "$[?(@.id==" + heads[0] + ")].rateBasis")).get(0)).isEqualTo("PER_PIECE");
        assertThat(mvc.perform(post("/api/v1/masters/expense-heads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rate Only\",\"rate\":3}")).andReturn().getResponse().getStatus()).as("a rate without a basis").isEqualTo(422);
        assertThat(mvc.perform(post("/api/v1/masters/expense-heads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basis Only\",\"rateBasis\":\"PER_PIECE\"}")).andReturn().getResponse().getStatus()).as("a basis without a rate").isEqualTo(422);
        assertThat(mvc.perform(post("/api/v1/masters/expense-heads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Per Kg\",\"rateBasis\":\"PER_KG\",\"rate\":3}")).andReturn().getResponse().getStatus()).as("an unknown basis").isEqualTo(422);
        assertThat(mvc.perform(post("/api/v1/masters/expense-heads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Negative\",\"rateBasis\":\"PER_PIECE\",\"rate\":-1}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/v1/masters/farmers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Farmer With Rate\",\"rateBasis\":\"PER_PIECE\",\"rate\":3}")).andReturn().getResponse().getStatus()).as("only an expense head has a rate").isEqualTo(422);
        // a later change of the head rate never alters a saved bill: the bill kept the rate it was calculated with
        String b = text(save(full(), null));
        long id = ((Number) JsonPath.read(b, "$.id")).longValue();
        mvc.perform(put("/api/v1/masters/expense-heads/" + heads[0]).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Wf Head 1\",\"rateBasis\":\"PER_PIECE\",\"rate\":9}")).andExpect(status().isOk());
        String again = body(getJson(mvc, "/api/v1/aadat/bills/" + id));
        assertThat(dec(again, "$.expenses[0].amount")).isEqualByComparingTo("87.5");
        assertThat(dec(again, "$.expenses[0].rate")).isEqualByComparingTo("2.5");
        // a NEW bill uses the changed configured rate: 35 pieces x 9 = 315, so the expense follows the configuration
        String changed = text(save(full(), null));
        assertThat(dec(changed, "$.expenses[0].rate")).isEqualByComparingTo("9");
        assertThat(dec(changed, "$.expenses[0].amount")).isEqualByComparingTo("315");
        assertThat(dec(changed, "$.totals.farmerSide.totalOfEnteredExpenses")).as("315 + 61.25 + 100 + 75.50 + 1000").isEqualByComparingTo("1551.75");
    }

    @Test
    void aLotWithoutWeightOrRateIsRefusedNotGuessed() throws Exception {
        MvcResult r = save(simple("2026-10-24", "").replace(",\"rate\":7", ""), null);
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(422);
        assertThat(nextExpected()).isEqualTo(1);
    }

    @Test
    void theRateIsEnteredPer100KgAndTheValueUsesTheRatePerKg() throws Exception {
        // onion sold at 30 rupees a kg is entered as 3000: 250 kg x 30 = 7500, and a purchaser of 40 kg at the same rate pays 1200 + 60 commission
        String json = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":5,\"weight\":250,\"rate\":3000,\"purchasers\":[{\"buyerId\":" + buyer1 + ",\"weight\":40,\"rate\":3000}]}],\"expenses\":[]}";
        String b = text(save(json, null));
        assertThat(dec(b, "$.lots[0].lotValue")).isEqualByComparingTo("7500");
        assertThat(dec(b, "$.lots[0].purchasers[0].value")).isEqualByComparingTo("1200");
        assertThat(dec(b, "$.lots[0].purchasers[0].commissionAmount")).isEqualByComparingTo("60");
        assertThat((String) JsonPath.read(b, "$.ruleSetId")).isEqualTo("OWNER_2026_10_25_Q100");
    }

    @Test
    void roundingIsHalfUpToTheRupeeForLotValuesAndOneDecimalForCommission() throws Exception {
        // rates are per 100 kg: 333 kg at 19.37 a kg (entered 1937) = 6450.21 -> 6450; 2.5 kg at 1 a kg (entered 100) = 2.5 -> 3 (half up);
        // commission of 6450 = 322.5, of 3 = 0.15 -> 0.2
        String json = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":1,\"weight\":333,\"rate\":1937,\"purchasers\":[{\"buyerId\":" + buyer1 + ",\"weight\":333,\"rate\":1937}]},"
                + "{\"itemId\":" + potato + ",\"pieces\":1,\"weight\":2.5,\"rate\":100,\"purchasers\":[{\"buyerId\":" + buyer1 + ",\"weight\":2.5,\"rate\":100}]}],\"expenses\":[]}";
        String b = text(save(json, null));
        assertThat(dec(b, "$.lots[0].lotValue")).isEqualByComparingTo("6450");
        assertThat(dec(b, "$.lots[1].lotValue")).isEqualByComparingTo("3");
        assertThat(dec(b, "$.lots[0].purchasers[0].commissionAmount")).isEqualByComparingTo("322.5");
        assertThat(dec(b, "$.lots[1].purchasers[0].commissionAmount")).isEqualByComparingTo("0.2");
        assertThat(dec(b, "$.totals.farmerSide.netOfEnteredAmounts")).isEqualByComparingTo("6453");
    }

    @Test
    void anAmountTooLargeToStoreIsRefusedWithAValidationErrorAndNothingIsSaved() throws Exception {
        // 999999999 kg x a rate of 999999999 per 100 kg is about 1e16, beyond the amount columns: refused, not a database error
        String lot = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":1,\"weight\":999999999,\"rate\":999999999,\"purchasers\":[]}],\"expenses\":[]}";
        MvcResult r = save(lot, null);
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(422);
        // a per-piece expense: 2 lots of 2000000000 pieces x a rate of 999999999 is also too large
        long bigHead = createExpenseHeadWithRate(mvc, "Wf Big Head", "999999999");
        String pieces = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":2000000000,\"weight\":1,\"rate\":1,\"purchasers\":[]},{\"itemId\":" + onion
                + ",\"pieces\":2000000000,\"weight\":1,\"rate\":1,\"purchasers\":[]}],\"expenses\":[{\"expenseHeadId\":" + bigHead + "}]}";
        MvcResult r2 = save(pieces, null);
        assertThat(r2.getResponse().getStatus()).as(text(r2)).isEqualTo(422);
        assertThat(nextExpected()).as("a refused bill must not consume a bill number").isEqualTo(1);
    }

    private String oneLot(String lotFields, String purchasers, String expenses) {
        return "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion + "," + lotFields
                + ",\"purchasers\":[" + purchasers + "]}],\"expenses\":[" + expenses + "]}";
    }

    @Test
    void invalidSubmissionsAreRefusedAndNothingIsSavedOrNumbered() throws Exception {
        String ok = "\"pieces\":1,\"weight\":10,\"rate\":100";
        String[][] cases = {
            {"a negative rate", oneLot("\"pieces\":1,\"weight\":10,\"rate\":-1", "", ""), "400"},
            {"a negative weight", oneLot("\"pieces\":1,\"weight\":-10,\"rate\":100", "", ""), "400"},
            {"negative pieces", oneLot("\"pieces\":-1,\"weight\":10,\"rate\":100", "", ""), "400"},
            {"fractional pieces", oneLot("\"pieces\":2.5,\"weight\":10,\"rate\":100", "", ""), "400"},
            {"more fraction digits than allowed", oneLot("\"pieces\":1,\"weight\":10.12345,\"rate\":100", "", ""), "400"},
            {"too many integer digits", oneLot("\"pieces\":1,\"weight\":10000000000,\"rate\":100", "", ""), "400"},
            {"a purchaser without a rate", oneLot(ok, "{\"buyerId\":" + buyer1 + ",\"weight\":5}", ""), "422"},
            {"a negative purchaser weight", oneLot(ok, "{\"buyerId\":" + buyer1 + ",\"weight\":-5,\"rate\":100}", ""), "400"},
            {"a per-piece expense on a bill without pieces", oneLot("\"weight\":10,\"rate\":100", "", "{\"expenseHeadId\":" + heads[0] + "}"), "422"},
            {"a typed expense without an amount", oneLot(ok, "", "{\"expenseHeadId\":" + heads[2] + "}"), "422"},
            {"a negative typed expense", oneLot(ok, "", "{\"expenseHeadId\":" + heads[2] + ",\"amount\":-1}"), "400"},
        };
        for (String[] c : cases) {
            MvcResult r = save(c[1], null);
            assertThat(r.getResponse().getStatus()).as(c[0] + ": " + text(r)).isEqualTo(Integer.parseInt(c[2]));
        }
        assertThat(nextExpected()).as("no refused bill may consume a bill number").isEqualTo(1);
        // zero quantities are valid: a zero weight is worth zero, and a per-piece expense on zero pieces needs pieces (refused above)
        String zero = text(save(oneLot("\"pieces\":0,\"weight\":0,\"rate\":100", "", ""), null));
        assertThat(dec(zero, "$.lots[0].lotValue")).isEqualByComparingTo("0");
    }

    @Test
    void numbersContinueInOneSeriesAcrossFinancialYears() throws Exception {
        assertThat(nextExpected()).isEqualTo(1);
        String a = text(save(simple("2026-03-31", ""), null));
        String c = text(save(simple("2026-04-01", ""), null));
        String d = text(save(simple("2027-01-15", ""), null));
        assertThat((Integer) JsonPath.read(a, "$.billNo")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(c, "$.billNo")).as("the new financial year does not restart the series").isEqualTo(2);
        assertThat((Integer) JsonPath.read(d, "$.billNo")).isEqualTo(3);
        assertThat((String) JsonPath.read(a, "$.financialYearLabel")).isEqualTo("2025-26");
        assertThat((String) JsonPath.read(c, "$.financialYearLabel")).isEqualTo("2026-27");
        assertThat(nextExpected()).isEqualTo(4);

        assertThat((Integer) JsonPath.read(body(getJson(mvc, "/api/v1/aadat/bills?billNo=2")), "$.content.length()")).isEqualTo(1);
        String fy2025 = body(getJson(mvc, "/api/v1/aadat/bills?financialYear=2025"));
        assertThat((Integer) JsonPath.read(fy2025, "$.content.length()")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(fy2025, "$.content[0].billNo")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(getJson(mvc, "/api/v1/aadat/bills?financialYear=2026")), "$.content.length()")).isEqualTo(2);
    }

    @Test
    void aDateOutsideTheChosenFinancialYearIsRefusedAndNothingIsSavedOrNumbered() throws Exception {
        MvcResult r = save(simple("2027-04-01", ",\"financialYear\":2026"), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(text(r)).contains("2026-27").contains("2027-04-01");
        MvcResult early = save(simple("2026-03-31", ",\"financialYear\":2026"), null);
        assertThat(early.getResponse().getStatus()).as("31 March belongs to the previous year").isEqualTo(422);
        assertThat(save(simple("1999-05-05", ""), null).getResponse().getStatus()).isEqualTo(422);
        assertThat(save(simple("2026-05-05", ",\"financialYear\":1900"), null).getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
        assertThat(nextExpected()).as("a refused save takes no number").isEqualTo(1);
        // the first and last day of the year are accepted
        assertThat(save(simple("2026-04-01", ",\"financialYear\":2026"), null).getResponse().getStatus()).isEqualTo(201);
        assertThat(save(simple("2027-03-31", ",\"financialYear\":2026"), null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void theServerRefusesWhatTheFormRefusesAndAFailedSaveTakesNoNumber() throws Exception {
        String ok = full();
        String[][] cases = {
            {"a purchaser without a buyer", ok.replace("\"buyerId\":" + buyer2 + ",\"pieces\":8", "\"buyerId\":null,\"pieces\":8"), "400"},
            {"a lot without a weight", ok.replace("\"weight\":1500", "\"weight\":null"), "422"},
            {"negative purchaser pieces", ok.replace("\"pieces\":12", "\"pieces\":-1"), "400"},
            {"too many decimals", ok.replace("\"weight\":600", "\"weight\":600.123456"), "400"},
            {"a purchaser row without weight and rate", ok.replace("\"pieces\":8,\"weight\":400,\"rate\":1800", "\"pieces\":8,\"weight\":null,\"rate\":null"), "422"},
            {"a per-piece expense when no lot has pieces", ok.replace("\"pieces\":20,", "\"pieces\":0,").replace("\"pieces\":15,", "\"pieces\":0,"), "422"},
            {"an amount-less expense whose head has no rate", ok.replace("{\"expenseHeadId\":" + heads[2] + ",\"amount\":100}", "{\"expenseHeadId\":" + heads[2] + "}"), "422"},
            {"a single buyer and purchasers on one lot", ok.replace("\"rate\":1800,\"marka\":\"M1\"", "\"rate\":1800,\"buyerId\":" + buyer1 + ",\"marka\":\"M1\""), "422"},
            {"the same expense head twice", ok.replace("{\"expenseHeadId\":" + heads[1] + "}", "{\"expenseHeadId\":" + heads[0] + "}"), "422"},
            {"bhada paid without an amount", ok.replace("\"bhadaAmount\":1000", "\"bhadaAmount\":null"), "422"},
            {"a bhada amount although bhada is not paid", ok.replace("\"bhadaPaid\":true", "\"bhadaPaid\":false"), "422"},
            {"an unknown purchaser", ok.replace("\"buyerId\":" + buyer1 + ",\"haste\"", "\"buyerId\":999999,\"haste\""), "422"},
            {"a bill type that does not exist", ok.replace("\"CREDIT\"", "\"LOAN\""), "400"},
        };
        for (String[] c : cases) {
            MvcResult r = save(c[1], null);
            assertThat(r.getResponse().getStatus()).as(c[0] + ": " + text(r)).isEqualTo(Integer.parseInt(c[2]));
        }
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).as("nothing was saved").isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_lot_buyer", Integer.class)).isZero();
        assertThat(nextExpected()).as("no refused save took a number").isEqualTo(1);

        // an inactive purchaser is refused too
        mvc.perform(patch("/api/v1/masters/buyers/" + buyer2 + "/active").contentType("application/json").content("{\"active\":false}"));
        assertThat(save(ok, null).getResponse().getStatus()).isEqualTo(422);
        assertThat(nextExpected()).isEqualTo(1);
    }

    @Test
    void aRetryReturnsTheSameNumberAndTakesNoSecondNumber() throws Exception {
        String key = "key-" + UUID.randomUUID();
        MvcResult first = save(full(), key);
        MvcResult again = save(full(), key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(text(first), "$.billNo")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(text(again), "$.billNo")).isEqualTo(1);
        assertThat(nextExpected()).as("the replay used no number").isEqualTo(2);
        // a conflicting reuse of the key changes nothing either
        assertThat(save(simple("2026-10-24", ""), key).getResponse().getStatus()).isEqualTo(409);
        assertThat(nextExpected()).isEqualTo(2);
    }

    @Test
    void concurrentSavesGetDistinctConsecutiveNumbersAndSimultaneousRetriesOfOneSaveLeaveNoGap() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 8 different bills at once
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String key = "key-" + UUID.randomUUID();
                results.add(pool.submit(() -> { go.await(); return save(simple("2026-10-24", ""), key); }));
            }
            go.countDown();
            Set<Integer> numbers = new HashSet<>();
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(201);
                numbers.add((Integer) JsonPath.read(text(r), "$.billNo"));
            }
            assertThat(numbers).as("distinct and consecutive, no duplicate and no gap").containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(nextExpected()).isEqualTo(9);

            // the SAME save sent 8 times at once: one bill, one number, and the losers give their number back
            String key = "key-" + UUID.randomUUID();
            CountDownLatch go2 = new CountDownLatch(1);
            List<Future<MvcResult>> same = new ArrayList<>();
            for (int i = 0; i < threads; i++) same.add(pool.submit(() -> { go2.await(); return save(simple("2026-10-25", ""), key); }));
            go2.countDown();
            Set<Integer> got = new HashSet<>();
            for (Future<MvcResult> f : same) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).as(text(r)).isIn(200, 201);
                got.add((Integer) JsonPath.read(text(r), "$.billNo"));
            }
            assertThat(got).containsExactly(9);
            assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isEqualTo(9);
            assertThat(nextExpected()).as("no number was lost to the losing retries").isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theStartingNumberCanBeSetBeforeTheFirstBillAndNeverAfter() throws Exception {
        assertThat(nextExpected()).isEqualTo(1);
        var req = put("/api/v1/firm/bill-numbering").contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en");
        assertThat(mvc.perform(req.content("{\"nextNumber\":0}")).andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(put("/api/v1/firm/bill-numbering").contentType(MediaType.APPLICATION_JSON).content("{\"nextNumber\":null}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        MvcResult set = mvc.perform(put("/api/v1/firm/bill-numbering").contentType(MediaType.APPLICATION_JSON).content("{\"nextNumber\":4001}")).andReturn();
        assertThat(set.getResponse().getStatus()).as(text(set)).isEqualTo(200);
        assertThat(nextExpected()).isEqualTo(4001);
        assertThat((Integer) JsonPath.read(text(save(simple("2026-10-24", ""), null)), "$.billNo")).isEqualTo(4001);
        MvcResult late = mvc.perform(put("/api/v1/firm/bill-numbering").contentType(MediaType.APPLICATION_JSON).content("{\"nextNumber\":10}")).andReturn();
        assertThat(late.getResponse().getStatus()).as("a firm with a numbered bill cannot move its series").isEqualTo(409);
        assertThat(nextExpected()).isEqualTo(4002);
        assertThat(body(getJson(mvc, "/api/v1/aadat/bills/next-number"))).contains("\"startLocked\":true");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'FIRM' and action = 'BILL_NUMBERING_START_SET'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aBillSavedBeforeV11IsStillReadableAndItsOldSingleBuyerIsShown() throws Exception {
        long firmId = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        long id = jdbc.queryForObject("insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id) "
                + "values ('2026-03-31', ?, 'NOT_CALCULATED', 'NONE', ?) returning id", Long.class, farmer, firmId);
        jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, pieces, weight, rate, buyer_id, firm_id) values (?, 1, ?, 5, 100, 12, ?, ?)", id, onion, buyer1, firmId);
        jdbc.update("insert into aadat_expense (bill_id, seq, expense_head_id, amount, firm_id) values (?, 1, ?, 7, ?)", id, heads[0], firmId);
        String b = body(getJson(mvc, "/api/v1/aadat/bills/" + id));
        assertThat((Object) JsonPath.read(b, "$.billNo")).isNull();
        assertThat((Object) JsonPath.read(b, "$.transport")).isNull();
        assertThat((Object) JsonPath.read(b, "$.billType")).isNull();
        assertThat((Object) JsonPath.read(b, "$.bhadaPaid")).isNull();
        assertThat((String) JsonPath.read(b, "$.financialYearLabel")).isEqualTo("2025-26");
        assertThat((String) JsonPath.read(b, "$.lots[0].buyer.name")).isEqualTo("Wf Buyer One");
        assertThat((Object) JsonPath.read(b, "$.lots[0].lotValue")).isNull();
        assertThat((Integer) JsonPath.read(b, "$.lots[0].purchasers.length()")).isZero();
        assertThat(dec(b, "$.totals.farmerSide.totalOfEnteredExpenses")).isEqualByComparingTo("7");
        assertThat((Integer) JsonPath.read(b, "$.totals.farmerSide.lotsWithoutEnteredValue")).isEqualTo(1);
        assertThat((String) JsonPath.read(b, "$.totals.basis")).as("an old bill keeps its entered amounts").isEqualTo("SUM_OF_ENTERED");
        String list = body(getJson(mvc, "/api/v1/aadat/bills"));
        assertThat((Object) JsonPath.read(list, "$.content[0].billNo")).isNull();
        // and the next bill still gets a number
        assertThat((Integer) JsonPath.read(text(save(simple("2026-10-24", ""), null)), "$.billNo")).isEqualTo(1);
    }

    @Test
    void theFirmPrintProfileIsReadByEveryMemberAndShownOnTheBill() throws Exception {
        String empty = body(getJson(mvc, "/api/v1/firm/profile"));
        assertThat((Object) JsonPath.read(empty, "$.address")).isNull();
        MvcResult upd = mvc.perform(put("/api/v1/firm/profile").contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")
                .content("{\"address\":\"Synthetic Road, Synthetic Town\",\"addressHi\":null,\"phone\":\"0000000000\",\"licenceNo\":\"L-1\",\"shopNo\":\"  \"}")).andReturn();
        assertThat(upd.getResponse().getStatus()).as(text(upd)).isEqualTo(200);
        String p = text(upd);
        assertThat((String) JsonPath.read(p, "$.licenceNo")).isEqualTo("L-1");
        assertThat((Object) JsonPath.read(p, "$.shopNo")).as("blank is stored as nothing").isNull();
        String bill = text(save(simple("2026-10-24", ""), null));
        assertThat((String) JsonPath.read(bill, "$.firm.address")).isEqualTo("Synthetic Road, Synthetic Town");
        assertThat((String) JsonPath.read(bill, "$.firm.phone")).isEqualTo("0000000000");
        assertThat(mvc.perform(put("/api/v1/firm/profile").contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"" + "x".repeat(301) + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'FIRM' and action = 'PROFILE_UPDATED'", Integer.class)).isEqualTo(1);
        jdbc.update("update firm set address = null, phone = null, licence_no = null where code = 'DEFAULT'");
    }
}
