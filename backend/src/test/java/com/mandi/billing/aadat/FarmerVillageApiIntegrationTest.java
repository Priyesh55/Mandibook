package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;

/** A farmer belongs to a village, and a bill is village first: the server checks it, whatever the browser does. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FarmerVillageApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long villageA, villageB, onion;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc);
        villageA = createMaster(mvc, "villages", "Fv Village A", null);
        villageB = createMaster(mvc, "villages", "Fv Village B", null);
        onion = itemId(jdbc, "Onion");
    }

    private long farmer(String name, Long village) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"nameHi\":null,\"villageId\":" + village + "}";
        String r = body(postJson(mvc, "/api/v1/masters/farmers", json, "en").andExpect(status().isCreated()));
        return ((Number) JsonPath.read(r, "$.id")).longValue();
    }

    private int billStatus(long farmerId, Long villageId) throws Exception {
        String json = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmerId + ",\"villageId\":" + villageId + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":1,\"weight\":1,\"rate\":10,\"purchasers\":[]}],\"expenses\":[]}";
        return postJson(mvc, "/api/v1/aadat/bills", json, "en").andReturn().getResponse().getStatus();
    }

    @Test
    void aFarmerIsCreatedUnderAVillageAndTheVillageIsReturnedWithTheName() throws Exception {
        long f = farmer("Fv Farmer 1", villageA);
        String list = body(getJson(mvc, "/api/v1/masters/farmers"));
        assertThat(((Number) JsonPath.read(list, "$[0].villageId")).longValue()).isEqualTo(villageA);
        assertThat((String) JsonPath.read(list, "$[0].villageName")).isEqualTo("Fv Village A");
        String page = body(getJson(mvc, "/api/v1/masters/farmers/page"));
        assertThat((String) JsonPath.read(page, "$.content[0].villageName")).isEqualTo("Fv Village A");
        assertThat(jdbc.queryForObject("select details from audit_event where entity_type = 'FARMER' and entity_id = ?", String.class, f)).contains("villageId=" + villageA);
    }

    @Test
    void theFarmerListOfAVillageContainsOnlyItsFarmers() throws Exception {
        farmer("Fv Farmer A1", villageA);
        farmer("Fv Farmer A2", villageA);
        farmer("Fv Farmer B1", villageB);
        createMaster(mvc, "farmers", "Fv No Village Farmer", null);           // a farmer without a village (entered before V12)
        List<String> a = JsonPath.read(body(getJson(mvc, "/api/v1/masters/farmers?villageId=" + villageA)), "$[*].name");
        assertThat(a).containsExactlyInAnyOrder("Fv Farmer A1", "Fv Farmer A2");
        List<String> pageB = JsonPath.read(body(getJson(mvc, "/api/v1/masters/farmers/page?activeOnly=true&villageId=" + villageB)), "$.content[*].name");
        assertThat(pageB).containsExactly("Fv Farmer B1");
        assertThat((Integer) JsonPath.read(body(getJson(mvc, "/api/v1/masters/farmers/page?villageId=" + villageB + "&q=zzz")), "$.content.length()")).isZero();
        assertThat((Integer) JsonPath.read(body(getJson(mvc, "/api/v1/masters/farmers")), "$.length()")).as("no filter: everyone").isEqualTo(4);
        assertThat((Integer) JsonPath.read(body(getJson(mvc, "/api/v1/masters/farmers?villageId=999999")), "$.length()")).isZero();
    }

    @Test
    void aVillageThatIsUnknownInactiveOrForeignIsRefused_andOnlyFarmersHaveOne() throws Exception {
        assertThat(postJson(mvc, "/api/v1/masters/farmers", "{\"name\":\"X\",\"villageId\":999999}", "en").andReturn().getResponse().getStatus()).isEqualTo(422);
        long otherFirm = jdbc.queryForObject("insert into firm (code, name) values ('FVOTHER', 'Other') returning id", Long.class);
        long foreign = jdbc.queryForObject("insert into village (name, firm_id) values ('Foreign Village', ?) returning id", Long.class, otherFirm);
        assertThat(postJson(mvc, "/api/v1/masters/farmers", "{\"name\":\"X\",\"villageId\":" + foreign + "}", "en").andReturn().getResponse().getStatus()).as("another firm's village").isEqualTo(422);
        mvc.perform(patch("/api/v1/masters/villages/" + villageB + "/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        assertThat(postJson(mvc, "/api/v1/masters/farmers", "{\"name\":\"X\",\"villageId\":" + villageB + "}", "en").andReturn().getResponse().getStatus()).as("inactive village").isEqualTo(422);
        assertThat(postJson(mvc, "/api/v1/masters/buyers", "{\"name\":\"B\",\"villageId\":" + villageA + "}", "en").andReturn().getResponse().getStatus()).as("a buyer has no village").isEqualTo(422);
        assertThat(jdbc.queryForObject("select count(*) from farmer", Integer.class)).isZero();
        jdbc.update("delete from village where firm_id = ?", otherFirm);
        jdbc.update("delete from firm where id = ?", otherFirm);
    }

    @Test
    void aFarmersVillageCanBeChangedAndTheChangeIsRecorded() throws Exception {
        long f = farmer("Fv Mover", villageA);
        mvc.perform(put("/api/v1/masters/farmers/" + f).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Fv Mover\",\"nameHi\":null,\"villageId\":" + villageB + "}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select village_id from farmer where id = ?", Long.class, f)).isEqualTo(villageB);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'FARMER' and entity_id = ? and action = 'UPDATED'", Integer.class, f)).isEqualTo(1);
    }

    @Test
    void aBillRefusesAFarmerOfAnotherVillageAndTakesNoNumber() throws Exception {
        long inA = farmer("Fv In A", villageA);
        long noVillage = createMaster(mvc, "farmers", "Fv No Village", null);
        assertThat(billStatus(inA, villageA)).isEqualTo(201);
        int wrong = billStatus(inA, villageB);
        assertThat(wrong).as("farmer of village A under village B").isEqualTo(422);
        assertThat(billStatus(noVillage, villageB)).as("a farmer who has no village yet (entered before V12) is not in conflict").isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isEqualTo(2);
        assertThat(body(getJson(mvc, "/api/v1/aadat/bills/next-number"))).contains("\"expectedNext\":3");
        String hi = body(postJson(mvc, "/api/v1/aadat/bills", "{\"billDate\":\"2026-10-24\",\"farmerId\":" + inA + ",\"villageId\":" + villageB + ",\"lots\":[{\"itemId\":" + onion + ",\"pieces\":1,\"weight\":1,\"rate\":1,\"purchasers\":[]}],\"expenses\":[]}", "hi"));
        assertThat(hi).contains("किसान");
    }
}
