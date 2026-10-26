package com.mandi.billing.aadat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** Helpers shared by the integration tests. Synthetic data only. */
public final class ApiTestSupport {

    private ApiTestSupport() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("delete from aadat_lot_buyer");
        jdbc.execute("delete from aadat_expense");
        jdbc.execute("delete from aadat_lot");
        jdbc.execute("delete from aadat_bill");
        jdbc.execute("delete from bill_number_counter");
        jdbc.execute("delete from farmer");
        jdbc.execute("delete from buyer");
        jdbc.execute("delete from village");
        jdbc.execute("delete from expense_head");
    }

    public static ResultActions postJson(MockMvc mvc, String url, String json, String lang) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", lang).content(json.getBytes(StandardCharsets.UTF_8)));
    }

    public static ResultActions getJson(MockMvc mvc, String url) throws Exception {
        return mvc.perform(get(url).header("Accept-Language", "en"));
    }

    public static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    public static long createMaster(MockMvc mvc, String type, String name, String nameHi) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"nameHi\":" + (nameHi == null ? "null" : "\"" + nameHi + "\"") + "}";
        ResultActions r = postJson(mvc, "/api/v1/masters/" + type, json, "en");
        return ((Number) JsonPath.read(body(r), "$.id")).longValue();
    }

    /** An expense head with a predefined rate per piece (bag): the bill multiplies its pieces by it. */
    public static long createExpenseHeadWithRate(MockMvc mvc, String name, String rate) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"nameHi\":null,\"rateBasis\":\"PER_PIECE\",\"rate\":" + rate + "}";
        return ((Number) JsonPath.read(body(postJson(mvc, "/api/v1/masters/expense-heads", json, "en")), "$.id")).longValue();
    }

    public static long itemId(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject(
                "select id from item where name = ? and firm_id = (select id from firm where code = 'DEFAULT')", Long.class, name);
    }
}
