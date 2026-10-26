package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.body;
import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.getJson;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;
import com.mandi.audit.AuditService;
import org.springframework.transaction.IllegalTransactionStateException;

/** Audit history: written with the change, queryable, and append-only at database level. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService auditService;

    long farmer, onion;

    @BeforeEach
    void setUp() throws Exception {
        clean(jdbc); // audit_event is intentionally not cleaned: it cannot be deleted
        farmer = createMaster(mvc, "farmers", "Audit Farmer", null);
        onion = itemId(jdbc, "Onion");
    }

    private List<Map<String, Object>> events(String type, long id) {
        return jdbc.queryForList("select * from audit_event where entity_type = ? and entity_id = ? order by id", type, id);
    }

    private int totalEvents() {
        return jdbc.queryForObject("select count(*) from audit_event", Integer.class);
    }

    private void putJson(String url, String json) throws Exception {
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
    }

    private void setActive(long id, boolean active) throws Exception {
        mvc.perform(patch("/api/v1/masters/farmers/" + id + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":" + active + "}")).andExpect(status().isOk());
    }

    @Test
    void masterChangesAreRecordedAndNoOpsAreNot() throws Exception {
        assertThat(events("FARMER", farmer)).hasSize(1);
        long who = jdbc.queryForObject("select id from app_user where email = 'feature-test-owner@example.test'", Long.class);
        // the actor is the signed-in person of the request, with membership, role at the time and the request id
        assertThat(events("FARMER", farmer).get(0)).containsEntry("action", "CREATED").containsEntry("actor", "user:" + who)
                .containsEntry("actor_user_id", who).containsEntry("actor_role", "OWNER");
        assertThat(events("FARMER", farmer).get(0).get("actor_membership_id")).isNotNull();
        assertThat(events("FARMER", farmer).get(0).get("request_id")).isNotNull();
        assertThat((String) events("FARMER", farmer).get(0).get("details")).contains("Audit Farmer");

        putJson("/api/v1/masters/farmers/" + farmer, "{\"name\":\"Audit Farmer\"}"); // identical: no event
        assertThat(events("FARMER", farmer)).hasSize(1);

        putJson("/api/v1/masters/farmers/" + farmer, "{\"name\":\"Audit Farmer Renamed\"}");
        List<Map<String, Object>> after = events("FARMER", farmer);
        assertThat(after).hasSize(2);
        assertThat(after.get(1)).containsEntry("action", "UPDATED");
        assertThat((String) after.get(1).get("details")).contains("Audit Farmer'").contains("Audit Farmer Renamed");

        setActive(farmer, false);
        setActive(farmer, false); // already inactive: no event
        setActive(farmer, true);
        assertThat(events("FARMER", farmer)).extracting(e -> e.get("action"))
                .containsExactly("CREATED", "UPDATED", "DEACTIVATED", "ACTIVATED");
    }

    private String billJson(String secondItemId) {
        return """
            {"billDate":"2026-10-17","farmerId":%d,"vehicleNo":"AUD-1",
             "lots":[{"itemId":%d,"pieces":1,"weight":10,"rate":2},{"itemId":%s,"pieces":2,"weight":4,"rate":3}],
             "expenses":[]}
            """.formatted(farmer, onion, secondItemId);
    }

    @Test
    void billCreationIsRecordedAndReadableThroughTheApi() throws Exception {
        String resp = body(postJson(mvc, "/api/v1/aadat/bills", billJson(String.valueOf(onion)), "en")
                .andExpect(status().isCreated()));
        long id = ((Number) JsonPath.read(resp, "$.id")).longValue();

        List<Map<String, Object>> rows = events("AADAT_BILL", id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", "CREATED");
        assertThat((String) rows.get(0).get("details")).contains("lots=2").contains("calculationStatus=CALCULATED").contains("AUD-1");

        getJson(mvc, "/api/v1/audit?entityType=AADAT_BILL&entityId=" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("CREATED"))
                .andExpect(jsonPath("$[0].actor").value("user:" + jdbc.queryForObject("select id from app_user where email = 'feature-test-owner@example.test'", Long.class)));
    }

    @Test
    void aFailedSaveLeavesNoBillNoLotsAndNoHistory() throws Exception {
        int eventsBefore = totalEvents();
        // Lot 1 is valid, lot 2 references a master record that does not exist: nothing may be stored.
        postJson(mvc, "/api/v1/aadat/bills", billJson("999999"), "en").andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_lot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_expense", Integer.class)).isZero();
        assertThat(totalEvents()).isEqualTo(eventsBefore);
    }

    @Test
    void historyCannotBeChangedOrRemovedEvenWithDirectDatabaseAccess() {
        assertThat(totalEvents()).isPositive();
        assertThatThrownBy(() -> jdbc.update("update audit_event set details = 'tampered'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate audit_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where details = 'tampered'", Integer.class)).isZero();
    }

    @Test
    void auditEndpointIsReadOnlyAndNeedsItsParameters() throws Exception {
        getJson(mvc, "/api/v1/audit").andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/audit?entityType=FARMER&entityId=" + farmer)).andExpect(status().is4xxClientError());
        assertThat(events("FARMER", farmer)).hasSize(1);
    }

    @Test
    void auditCannotBeWrittenOutsideTheTransactionOfTheChangeItDescribes() {
        int before = totalEvents();
        assertThatThrownBy(() -> auditService.record("FARMER", farmer, "UPDATED", "stray event"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(totalEvents()).isEqualTo(before);
    }
}
