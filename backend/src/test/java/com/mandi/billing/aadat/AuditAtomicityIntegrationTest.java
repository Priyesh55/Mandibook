package com.mandi.billing.aadat;

import static com.mandi.billing.aadat.ApiTestSupport.clean;
import static com.mandi.billing.aadat.ApiTestSupport.createMaster;
import static com.mandi.billing.aadat.ApiTestSupport.itemId;
import static com.mandi.billing.aadat.ApiTestSupport.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.mandi.TestcontainersConfiguration;
import com.mandi.audit.AuditService;

/**
 * Proves the change and its history are one unit: when writing the audit event fails AFTER the bill, lots and
 * expenses were already written, the whole bill is rolled back. (Removing the shared transaction would let the
 * bill survive and fail this test.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditAtomicityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuditService audit;

    @BeforeEach
    void setUp() {
        clean(jdbc);
    }

    @Test
    void aFailureWhileRecordingHistoryRollsBackTheWholeBill() throws Exception {
        long farmer = createMaster(mvc, "farmers", "Atomic Farmer", null);
        long onion = itemId(jdbc, "Onion");
        long head = createMaster(mvc, "expense-heads", "Atomic Expense", null);

        // Stub the spy behind the transactional proxy: calling the proxy here would itself trip the
        // mandatory-transaction check (we are outside a transaction in the test).
        AuditService target = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("simulated audit failure"))
                .when(target).record(eq("AADAT_BILL"), any(), any(), any());

        String json = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmer + ",\"lots\":[{\"itemId\":" + onion
                + ",\"pieces\":1,\"weight\":5,\"rate\":2}],\"expenses\":[{\"expenseHeadId\":" + head + ",\"amount\":10}]}";
        postJson(mvc, "/api/v1/aadat/bills", json, "en").andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from aadat_bill", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_lot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_expense", Integer.class)).isZero();
    }
}
