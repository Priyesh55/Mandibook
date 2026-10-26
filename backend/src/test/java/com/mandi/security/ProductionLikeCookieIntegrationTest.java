package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.mandi.TestcontainersConfiguration;
import com.mandi.identity.AppUser;
import com.mandi.identity.UserAccountService;
import com.mandi.security.HttpTestClient.Response;

/**
 * The cookie settings the production-like baseline file sets (application.properties): the same flow as the development
 * tests, with the cookie properties of the baseline switched back on (this test profile runs over plain http, so they are
 * restated here). Only the headers differ; the client jar ignores the Secure flag so the flow can continue over http.
 * StartupSafetyTest proves that the baseline file really contains these values and that weaker ones refuse to start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.name=__Host-mandi_session",
        "server.servlet.session.cookie.secure=true",
        "server.servlet.session.cookie.http-only=true",
        "server.servlet.session.cookie.same-site=lax",
        "server.servlet.session.cookie.path=/"})
@Import(TestcontainersConfiguration.class)
class ProductionLikeCookieIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestcontainersConfiguration.registerDatabaseProperties(registry);
    }

    @LocalServerPort int port;
    @Autowired UserAccountService accounts;

    @Test
    void theSessionAndCsrfCookiesCarryTheProductionAttributes() {
        String email = "prodlike-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        AppUser u = accounts.create(email, "Prod-like", "correct-horse-battery");
        HttpTestClient b = new HttpTestClient(port);
        Response csrf = b.get("/api/v1/auth/csrf");
        assertThat((Object) csrf.json("$.cookieName")).isEqualTo("__Host-XSRF-TOKEN");
        String csrfCookie = csrf.setCookies().stream().filter(c -> c.startsWith("__Host-XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(csrfCookie).contains("Secure").contains("SameSite=Lax").contains("Path=/").doesNotContain("HttpOnly").doesNotContain("Domain");

        Response login = b.login(email, "correct-horse-battery", csrf.json("$.token"));
        assertThat(login.status()).isEqualTo(200);
        String session = login.setCookies().stream().filter(c -> c.startsWith("__Host-mandi_session=")).findFirst().orElseThrow();
        assertThat(session).contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContain("Domain").doesNotContain("Max-Age").doesNotContain("Expires");
        // the rotated CSRF cookie keeps the attributes
        String rotated = login.setCookies().stream().filter(c -> c.startsWith("__Host-XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(rotated).contains("Secure").contains("SameSite=Lax").contains("Path=/");

        assertThat(b.get("/api/v1/auth/me").status()).isEqualTo(200);
        Response out = b.post("/api/v1/auth/logout", null, "X-XSRF-TOKEN", b.jar.get("__Host-XSRF-TOKEN"));
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookies()).as("both cookies are expired on logout").anyMatch(c -> c.startsWith("__Host-mandi_session=") && c.contains("Max-Age=0"))
                .anyMatch(c -> c.startsWith("__Host-XSRF-TOKEN=;") && c.contains("1970"));
    }
}
