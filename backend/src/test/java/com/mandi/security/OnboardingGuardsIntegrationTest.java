package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mandi.TestcontainersConfiguration;
import com.mandi.identity.OneTimeTokens;
import com.mandi.security.HttpTestClient.Response;

/**
 * What the provisioning does when its secure configuration is missing, as in a production-like installation that has no
 * email provider: the development mail sink is OFF, so nobody can be invited, nothing is stored, and the dev endpoint that
 * shows links does not exist. Also the throttle on the public accept endpoint. Disposable database, synthetic data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mandi.mail.dev-sink=false",
        "mandi.security.login.max-attempts-per-ip=5"})
class OnboardingGuardsIntegrationTest {

    private static final String URL = TestcontainersConfiguration.newDatabaseUrl();
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void disposableDatabase(DynamicPropertyRegistry registry) {
        TestcontainersConfiguration.registerDatabaseProperties(registry, () -> URL);
    }

    @LocalServerPort int port;
    @Autowired com.mandi.identity.UserAccountService accounts;
    private final JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(URL,
            TestcontainersConfiguration.postgres().getUsername(), TestcontainersConfiguration.postgres().getPassword()));

    @org.junit.jupiter.api.BeforeEach
    void independentTests() {
        owner.update("delete from auth_throttle");
    }

    @Test
    void withoutAMailProviderNobodyCanBeInvitedAndNothingIsStored() {
        // an owner exists (invited from the platform side, accepted through the application) ...
        String email = "guard-owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        long account = owner.queryForObject("insert into platform.owner_account (display_name, status) values ('Guard Org', 'ACTIVE') returning id", Long.class);
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        owner.update("insert into platform.owner_invitation (owner_account_id, email, token_hash, expires_at) values (?, ?, ?, now() + interval '7 days')", account, email, t.hash());
        HttpTestClient anon = new HttpTestClient(port);
        assertThat(anon.post("/api/v1/auth/invitations/accept", "{\"token\":\"" + t.raw() + "\",\"password\":\"" + PASSWORD + "\"}", CSRF_HEADER, anon.csrf()).status()).isEqualTo(200);
        HttpTestClient o = new HttpTestClient(port);
        assertThat(o.signIn(email, PASSWORD).status()).isEqualTo(200);
        Response firm = o.post("/api/v1/auth/firms", "{\"name\":\"Guard Firm\"}", CSRF_HEADER, o.jar.get("XSRF-TOKEN"));
        assertThat(firm.status()).isEqualTo(201);
        long firmId = ((Number) firm.json("$.id")).longValue();
        assertThat(o.post("/api/v1/auth/active-firm", "{\"firmId\":" + firmId + "}", CSRF_HEADER, o.jar.get("XSRF-TOKEN")).status()).isEqualTo(200);

        // ... but there is no way to deliver an invitation, so none is created
        Response invite = o.post("/api/v1/firm/invitations", "{\"email\":\"employee@example.test\"}", CSRF_HEADER, o.jar.get("XSRF-TOKEN"), "X-Mandi-Firm-Check", String.valueOf(firmId));
        assertThat(invite.status()).isEqualTo(503);
        assertThat(invite.body()).isEqualTo("{\"error\":\"EMAIL_NOT_CONFIGURED\"}");
        assertThat(owner.queryForObject("select count(*) from firm_invitation", Integer.class)).as("nothing stored that nobody can receive").isZero();
        assertThat(owner.queryForObject("select count(*) from audit_event where entity_type = 'FIRM_INVITATION'", Integer.class)).isZero();

        // the development endpoint that would show links does not exist in this configuration
        Response dev = new HttpTestClient(port).get("/api/v1/dev/mail");
        assertThat(dev.status()).isEqualTo(401);
        assertThat(dev.body()).doesNotContain("link");
    }

    @Test
    void theAcceptEndpointIsThrottledLikeSignIn() {
        HttpTestClient b = new HttpTestClient(port);
        String csrf = b.csrf();
        int last = 0;
        for (int i = 1; i <= 6; i++) {
            last = b.post("/api/v1/auth/invitations/accept", "{\"token\":\"" + OneTimeTokens.issue().raw() + "\",\"password\":\"" + PASSWORD + "\"}", CSRF_HEADER, csrf).status();
            if (i <= 5) assertThat(last).as("attempt " + i).isEqualTo(400);
        }
        assertThat(last).as("the 6th attempt from one address in the window").isEqualTo(429);
    }
}
