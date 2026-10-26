package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.mandi.TestcontainersConfiguration;
import com.mandi.identity.AppUser;
import com.mandi.identity.PasswordService;
import com.mandi.identity.UserAccountService;
import com.mandi.security.HttpTestClient.Response;

/**
 * Sign-in, sessions and CSRF over REAL HTTP against the embedded server, the real Spring Security chain, the real
 * Spring Session JDBC store in PostgreSQL and the real BCrypt: no mocks, no MockMvc, no bypass. The 18-hour limit is
 * tested with a hand-moved clock and the idle timeout by moving the stored session times (both would otherwise need
 * hours). All users are synthetic fixtures created by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mandi.security.login.max-attempts-per-email=5",
        "mandi.security.login.max-attempts-per-ip=30",
        "mandi.security.login.max-failures-before-lock=3",
        "mandi.security.login.max-concurrent-hashes=3",
        "mandi.security.login.hash-wait-timeout=PT0.2S"})
@Import({TestcontainersConfiguration.class, AuthenticationFlowIntegrationTest.TestClock.class})
class AuthenticationFlowIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private static final String PASSWORD = "correct-horse-battery";
    private static final String SESSION_COOKIE = "mandi_session";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestcontainersConfiguration.registerDatabaseProperties(registry);
    }

    @LocalServerPort int port;
    @Autowired MutableClock clock;
    @Autowired UserAccountService accounts;
    @Autowired PasswordService passwords;
    @Autowired AccountSecurityService accountSecurity;
    @Autowired HashLimiter limiter;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    /** the OWNER connection: fixtures and assertions only */
    @Autowired JdbcTemplate owner;

    @BeforeEach
    void independentTests() {
        clock.reset();
        owner.update("delete from auth_throttle");
    }

    private HttpTestClient browser() {
        return new HttpTestClient(port);
    }

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private AppUser user(String tag, String password) {
        return accounts.create(email(tag), "Synthetic " + tag, password);
    }

    /** A firm owned by the user (OWNER membership); used as a harmless state-changing target: selecting it. */
    private long ownedFirm(AppUser u) {
        long firm = owner.queryForObject("insert into firm (code, name) values (?, ?) returning id", Long.class,
                "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "Synthetic firm of " + u.getId());
        owner.update("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER')", firm, u.getId());
        return firm;
    }

    private static String select(long firm) {
        return "{\"firmId\":" + firm + "}";
    }

    private HttpTestClient signedIn(String email, String password) {
        HttpTestClient b = browser();
        Response r = b.signIn(email, password);
        assertThat(r.status()).as("sign-in of " + email).isEqualTo(200);
        return b;
    }

    private String sessionId(HttpTestClient b) {
        return new String(Base64.getDecoder().decode(b.jar.get(SESSION_COOKIE)), StandardCharsets.UTF_8);
    }

    private int events(String type, Long userId) {
        return owner.queryForObject("select count(*) from auth_event where event_type = ? and user_id is not distinct from ?", Integer.class, type, userId);
    }

    // ---- who may reach what without signing in --------------------------------------------------------------------

    @Test
    void everyBusinessRouteRequiresASessionAndOnlyTheApprovedEndpointsArePublic() {
        HttpTestClient anon = browser();
        String token = anon.csrf();                      // a valid CSRF token, so unsafe methods reach the authorization step
        Set<String> publicRoutes = Set.of("GET /api/v1/auth/csrf", "POST /api/v1/auth/login", "POST /api/v1/auth/logout",
                "POST /api/v1/auth/invitations/accept", "GET /api/v1/dev/mail");   // the last exists only with the development mail sink
        List<String> checked = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, ?> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
            List<String> verbs = methods.isEmpty() ? List.of("GET") : methods.stream().map(Enum::name).toList();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                String path = pattern.replace("{type}", "farmer").replaceAll("\\{[^}]+}", "1");
                for (String verb : verbs) {
                    String route = verb + " " + pattern;
                    if (publicRoutes.contains(verb + " " + path)) continue;
                    Response r = anon.send(verb, path, verb.equals("GET") ? null : "{}", CSRF_HEADER, token);
                    assertThat(r.status()).as("anonymous " + route).isEqualTo(401);
                    assertThat(r.body()).as(route).contains("UNAUTHENTICATED");
                    checked.add(route);
                }
            }
        }
        assertThat(checked).as("the enumeration actually covered the business API").hasSizeGreaterThanOrEqualTo(10)
                .anyMatch(r -> r.contains("/api/v1/masters")).anyMatch(r -> r.contains("/api/v1/aadat/bills")).anyMatch(r -> r.contains("/api/v1/audit"));
        // the public ones answer, and nothing else does
        assertThat(anon.get("/api/v1/auth/csrf").status()).isEqualTo(200);
        assertThat(anon.get("/actuator/health").status()).isEqualTo(200);
        for (String hidden : List.of("/actuator/info", "/actuator/env", "/", "/index.html", "/h2-console", "/api/v1/auth/me", "/api/unknown")) {
            assertThat(anon.get(hidden).status()).as(hidden).isEqualTo(401);
        }
    }

    // ---- sign-in -------------------------------------------------------------------------------------------------

    @Test
    void validCredentialsSignInAndOpenTheProtectedApi() {
        AppUser u = user("ok", PASSWORD);
        HttpTestClient b = browser();
        assertThat(b.get("/api/v1/auth/me").status()).as("before sign-in").isEqualTo(401);
        Response login = b.signIn(u.getEmail(), PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat((Object) login.json("$.user.email")).isEqualTo(u.getEmail());
        assertThat(login.body()).doesNotContain(PASSWORD).doesNotContain("password");

        assertThat(b.get("/api/v1/auth/me").status()).as("after sign-in").isEqualTo(200);
        Response noFirm = b.get("/api/v1/masters/farmers");
        assertThat(noFirm.status()).as("signed in, but no firm yet: the business API answers 409").isEqualTo(409);
        assertThat(noFirm.body()).isEqualTo("{\"error\":\"FIRM_SELECTION_REQUIRED\"}");
        Response me = b.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat((Object) me.json("$.user.id")).isEqualTo(u.getId().intValue());
        assertThat(me.header("X-Session-Absolute-Expires-At")).as("expiry moments on every response").isNotNull();
        assertThat(me.header("X-Session-Idle-Expires-At")).isNotNull();

        assertThat(owner.queryForObject("select principal_name from spring_session where session_id = ?", String.class, sessionId(b)))
                .isEqualTo("u:" + u.getId());
        assertThat(owner.queryForObject("select max_inactive_interval from spring_session where session_id = ?", Integer.class, sessionId(b)))
                .as("idle timeout 30 minutes").isEqualTo(1800);
        assertThat(events("LOGIN_SUCCESS", u.getId())).isEqualTo(1);
        assertThat(owner.queryForObject("select last_login_at is not null from app_user where id = ?", Boolean.class, u.getId())).isTrue();
    }

    @Test
    void everyKindOfWrongCredentialGivesTheSameGenericAnswer() {
        AppUser u = user("generic", PASSWORD);
        AppUser disabled = user("disabled", PASSWORD);
        owner.update("update app_user set status = 'DISABLED' where id = ?", disabled.getId());
        AppUser locked = user("locked", PASSWORD);
        owner.update("update app_user set locked_until = now() + interval '10 minutes' where id = ?", locked.getId());
        String stranger = email("stranger");

        List<Response> answers = new ArrayList<>();
        answers.add(browser().signIn(u.getEmail(), "wrong-password-xx"));        // wrong password
        answers.add(browser().signIn(stranger, PASSWORD));                       // unknown email
        answers.add(browser().signIn(disabled.getEmail(), PASSWORD));            // disabled, right password
        answers.add(browser().signIn(locked.getEmail(), PASSWORD));              // locked, right password
        answers.add(browser().signIn("not-an-email", PASSWORD));                 // malformed
        answers.add(browser().signIn(u.getEmail().toUpperCase(), "")); // empty password
        for (Response r : answers) {
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.body()).isEqualTo("{\"error\":\"INVALID_CREDENTIALS\"}");
            assertThat(r.setCookies()).as("no session for a failed sign-in").noneMatch(c -> c.startsWith(SESSION_COOKIE + "="));
        }
        assertThat(browser().post("/api/v1/auth/login", "{}", CSRF_HEADER, "x").status()).as("a request without fields").isIn(400, 403);
    }

    @Test
    void passwordsAtAndOverThe72ByteLimitAreVerifiedWithoutTruncation() {
        String hindi24 = "क".repeat(24);                       // exactly 72 bytes
        AppUser h = user("hindi", hindi24);
        assertThat(browser().signIn(h.getEmail(), hindi24).status()).as("72 bytes, 24 Hindi characters").isEqualTo(200);
        assertThat(browser().signIn(h.getEmail(), hindi24 + "क").status()).as("the same 72 bytes plus one more character").isEqualTo(401);
        String ascii72 = "p".repeat(72);
        AppUser a = user("ascii", ascii72);
        assertThat(browser().signIn(a.getEmail(), ascii72).status()).isEqualTo(200);
        assertThat(browser().signIn(a.getEmail(), ascii72 + "x").status()).as("73 bytes must not match the 72-byte password").isEqualTo(401);
        assertThat(browser().signIn(a.getEmail(), "p".repeat(71)).status()).isEqualTo(401);
    }

    @Test
    void platformAdministratorCredentialsCannotSignInThroughTheBusinessLogin() {
        String adminEmail = email("platform-admin");
        owner.update("insert into platform.platform_admin (email, password_hash) values (?, ?)", adminEmail, passwords.hash("platform-admin-secret"));
        Response r = browser().signIn(adminEmail, "platform-admin-secret");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"INVALID_CREDENTIALS\"}");
        // even when a business user shares the email, only the business password works
        AppUser same = accounts.create(adminEmail, "Same email", "business-user-password");
        assertThat(browser().signIn(adminEmail, "platform-admin-secret").status()).isEqualTo(401);
        HttpTestClient b = signedIn(adminEmail, "business-user-password");
        // and the resulting session is a business session: the principal is the app_user, with the business authority only
        assertThat((Object) b.get("/api/v1/auth/me").json("$.user.id")).isEqualTo(same.getId().intValue());
    }

    // ---- sessions: rotation, logout -------------------------------------------------------------------------------

    @Test
    void everySignInGetsANewSessionAndTheOldOneStopsWorking() {
        AppUser u = user("rotate", PASSWORD);
        HttpTestClient b = browser();
        assertThat(b.signIn(u.getEmail(), PASSWORD).status()).isEqualTo(200);
        String first = b.jar.get(SESSION_COOKIE);
        String csrfBefore = b.jar.get(CSRF_COOKIE);
        assertThat(b.signIn(u.getEmail(), PASSWORD).status()).isEqualTo(200);      // signs in again with the same browser
        String second = b.jar.get(SESSION_COOKIE);
        assertThat(second).as("a new session identifier").isNotEqualTo(first);
        assertThat(b.jar.get(CSRF_COOKIE)).as("and a new CSRF token").isNotEqualTo(csrfBefore);
        assertThat(owner.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class, "u:" + u.getId()))
                .as("the previous session is gone from the store").isEqualTo(1);
        HttpTestClient old = browser();
        old.jar.put(SESSION_COOKIE, first);
        assertThat(old.get("/api/v1/auth/me").status()).as("the old identifier is dead").isEqualTo(401);
    }

    @Test
    void aPlantedSessionIdentifierIsNeverAdopted() {
        AppUser u = user("fixation", PASSWORD);
        HttpTestClient attacker = browser();
        String planted = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        HttpTestClient victim = browser();
        victim.jar.put(SESSION_COOKIE, planted);
        assertThat(victim.signIn(u.getEmail(), PASSWORD).status()).isEqualTo(200);
        assertThat(victim.jar.get(SESSION_COOKIE)).isNotEqualTo(planted);
        attacker.jar.put(SESSION_COOKIE, planted);
        assertThat(attacker.get("/api/v1/auth/me").status()).as("the planted identifier gives nothing").isEqualTo(401);
    }

    @Test
    void logoutEndsTheServerSessionAndNeedsTheCsrfToken() {
        AppUser u = user("logout", PASSWORD);
        HttpTestClient b = signedIn(u.getEmail(), PASSWORD);
        String cookie = b.jar.get(SESSION_COOKIE);
        String id = sessionId(b);
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("no CSRF token").isEqualTo(403);
        assertThat(b.get("/api/v1/auth/me").status()).as("still signed in").isEqualTo(200);

        Response out = b.post("/api/v1/auth/logout", null, CSRF_HEADER, b.jar.get(CSRF_COOKIE));
        assertThat(out.status()).isEqualTo(204);
        assertThat(owner.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class, id)).isZero();
        assertThat(b.jar).as("the browser's cookies are cleared").doesNotContainKey(SESSION_COOKIE).doesNotContainKey(CSRF_COOKIE);
        HttpTestClient replay = browser();
        replay.jar.put(SESSION_COOKIE, cookie);
        assertThat(replay.get("/api/v1/auth/me").status()).as("the old cookie replayed").isEqualTo(401);
        assertThat(events("LOGOUT", u.getId())).isEqualTo(1);
    }

    // ---- CSRF and origin -------------------------------------------------------------------------------------------

    @Test
    void stateChangingRequestsNeedTheCsrfTokenAndTheTokenOfTheCurrentSignIn() {
        AppUser u = user("csrf", PASSWORD);
        long firm = ownedFirm(u);
        HttpTestClient b = browser();
        String preLoginToken = b.csrf();
        String creds = "{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}";
        Response noHeader = b.post("/api/v1/auth/login", creds);
        assertThat(noHeader.status()).isEqualTo(403);
        assertThat(noHeader.body()).isEqualTo("{\"error\":\"CSRF_REJECTED\"}");
        assertThat(b.post("/api/v1/auth/login", creds, CSRF_HEADER, "not-the-token").status()).isEqualTo(403);
        assertThat(b.post("/api/v1/auth/login", creds, CSRF_HEADER, preLoginToken).status()).isEqualTo(200);

        // the token from before the sign-in was replaced by it: it must not work any more
        assertThat(b.jar.get(CSRF_COOKIE)).isNotEqualTo(preLoginToken);
        String body = select(firm);
        assertThat(b.post("/api/v1/auth/active-firm", body).status()).as("signed in but no CSRF header").isEqualTo(403);
        assertThat(b.post("/api/v1/auth/active-firm", body, CSRF_HEADER, preLoginToken).status()).as("the pre-sign-in token").isEqualTo(403);
        assertThat(b.post("/api/v1/auth/active-firm", body, CSRF_HEADER, b.jar.get(CSRF_COOKIE)).status()).as("the current token").isEqualTo(200);
        // a safe request needs no token
        assertThat(b.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void aForeignOriginIsRefusedEvenWithAValidToken() {
        AppUser u = user("origin", PASSWORD);
        long firm = ownedFirm(u);
        HttpTestClient b = signedIn(u.getEmail(), PASSWORD);
        String token = b.jar.get(CSRF_COOKIE);
        String body = select(firm);
        for (String origin : List.of("http://evil.example", "null", "http://localhost:3001", "https://localhost:3000")) {
            Response r = b.post("/api/v1/auth/active-firm", body, CSRF_HEADER, token, "Origin", origin);
            assertThat(r.status()).as("Origin " + origin).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"error\":\"ORIGIN_NOT_ALLOWED\"}");
        }
        assertThat(b.post("/api/v1/auth/active-firm", body, CSRF_HEADER, token, "Origin", "http://localhost:3000").status()).as("the configured origin").isEqualTo(200);
        Response login = browser().post("/api/v1/auth/login", "{}", CSRF_HEADER, "x", "Origin", "http://evil.example");
        assertThat(login.status()).as("also on the login").isEqualTo(403);
    }

    @Test
    void theSessionCookieIsHttpOnlyLaxAndHasNoLifetimeAndTheCsrfCookieIsReadableByThePage() {
        AppUser u = user("cookies", PASSWORD);
        HttpTestClient b = browser();
        Response csrfResponse = b.get("/api/v1/auth/csrf");
        String csrfCookie = csrfResponse.setCookies().stream().filter(c -> c.startsWith(CSRF_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(csrfCookie).contains("Path=/").contains("SameSite=Lax").doesNotContain("HttpOnly");
        Response login = b.login(u.getEmail(), PASSWORD, csrfResponse.json("$.token"));
        String session = login.setCookies().stream().filter(c -> c.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContain("Max-Age").doesNotContain("Expires").doesNotContain("Domain")
                .as("development profile over plain http: not Secure").doesNotContain("Secure");
    }

    // ---- expiry ------------------------------------------------------------------------------------------------------

    @Test
    void theSessionEndsAfter18HoursWhateverTheUserDoesInBetween() {
        AppUser u = user("absolute", PASSWORD);
        HttpTestClient b = signedIn(u.getEmail(), PASSWORD);
        String id = sessionId(b);
        Response me = b.get("/api/v1/auth/me");
        assertThat(java.time.Instant.parse(me.header("X-Session-Absolute-Expires-At")))
                .isBetween(clock.instant().plus(Duration.ofHours(18)).minusSeconds(5), clock.instant().plus(Duration.ofHours(18)).plusSeconds(5));
        // active every six hours: activity must not extend the limit
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofHours(6));
            assertThat(b.get("/api/v1/auth/me").status()).as("after " + (i + 1) * 6 + " hours").isEqualTo(200);
        }
        clock.advance(Duration.ofHours(5).plusMinutes(59));
        assertThat(b.get("/api/v1/auth/me").status()).as("17 hours 59 minutes").isEqualTo(200);
        clock.advance(Duration.ofMinutes(2));
        Response expired = b.get("/api/v1/auth/me");
        assertThat(expired.status()).as("18 hours 1 minute").isEqualTo(401);
        assertThat(expired.body()).isEqualTo("{\"error\":\"SESSION_EXPIRED\"}");
        assertThat(owner.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class, id)).as("the session is destroyed").isZero();
        assertThat(b.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(events("SESSION_ABSOLUTE_EXPIRED", u.getId())).isEqualTo(1);
        // signing in again starts a new 18 hours
        assertThat(b.signIn(u.getEmail(), PASSWORD).status()).isEqualTo(200);
        assertThat(b.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void anIdleSessionEndsAfter30MinutesAndActivityPushesTheIdleLimitForward() {
        AppUser u = user("idle", PASSWORD);
        HttpTestClient b = signedIn(u.getEmail(), PASSWORD);
        String id = sessionId(b);
        long thirtyOneMinutes = Duration.ofMinutes(31).toMillis(), twentyMinutes = Duration.ofMinutes(20).toMillis();

        shiftStoredSession(id, twentyMinutes);
        long before = expiryOf(id);
        assertThat(b.get("/api/v1/auth/me").status()).as("idle for 20 minutes: still valid").isEqualTo(200);
        assertThat(expiryOf(id)).as("the request pushed the idle limit forward").isGreaterThan(before + Duration.ofMinutes(15).toMillis());

        shiftStoredSession(id, thirtyOneMinutes);
        assertThat(b.get("/api/v1/auth/me").status()).as("idle for 31 minutes").isEqualTo(401);
        assertThat(owner.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class, id)).isZero();
    }

    private void shiftStoredSession(String sessionId, long millisIntoThePast) {
        owner.update("update spring_session set last_access_time = last_access_time - ?, expiry_time = expiry_time - ?, creation_time = creation_time - ? where session_id = ?",
                millisIntoThePast, millisIntoThePast, millisIntoThePast, sessionId);
    }

    private long expiryOf(String sessionId) {
        return owner.queryForObject("select expiry_time from spring_session where session_id = ?", Long.class, sessionId);
    }

    private long lastAccessOf(String sessionId) {
        return owner.queryForObject("select last_access_time from spring_session where session_id = ?", Long.class, sessionId);
    }

    @Test
    void askingHowLongTheSessionHasLeftNeverExtendsIt() {
        AppUser u = user("passive", PASSWORD);
        HttpTestClient b = signedIn(u.getEmail(), PASSWORD);
        String id = sessionId(b);
        shiftStoredSession(id, Duration.ofMinutes(10).toMillis());
        long lastAccess = lastAccessOf(id);
        for (int i = 0; i < 3; i++) {
            Response r = b.get("/api/v1/auth/session");
            assertThat(r.status()).isEqualTo(200);
            assertThat((Object) r.json("$.status")).isEqualTo("ok");
            assertThat(((Number) r.json("$.idleSecondsLeft")).longValue()).as("about 20 minutes left").isBetween(1100L, 1205L);
            assertThat(((Number) r.json("$.absoluteSecondsLeft")).longValue()).isBetween(64000L - 700, 64800L);
        }
        assertThat(lastAccessOf(id)).as("the status endpoint did not count as activity").isEqualTo(lastAccess);
        assertThat(b.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(lastAccessOf(id)).as("an ordinary request does").isGreaterThan(lastAccess);
        // it reports nothing for someone who is not signed in and creates no session
        int sessions = owner.queryForObject("select count(*) from spring_session", Integer.class);
        Response anon = browser().get("/api/v1/auth/session");
        assertThat((Object) anon.json("$.status")).isEqualTo("none");
        assertThat(owner.queryForObject("select count(*) from spring_session", Integer.class)).isEqualTo(sessions);
    }

    // ---- throttling and bounded work -----------------------------------------------------------------------------------

    @Test
    void tooManyAttemptsAreThrottledBeforeAnyPasswordWorkIsDone() {
        AppUser u = user("throttle", PASSWORD);
        HttpTestClient b = browser();
        String token = b.csrf();
        for (int i = 1; i <= 5; i++) {
            assertThat(b.login(u.getEmail(), "wrong-password-" + i, token).status()).as("attempt " + i).isEqualTo(401);
        }
        Response sixth = b.login(u.getEmail(), PASSWORD, token);
        assertThat(sixth.status()).as("6th attempt in the window, even with the right password").isEqualTo(429);
        assertThat(sixth.body()).isEqualTo("{\"error\":\"TOO_MANY_ATTEMPTS\"}");
        assertThat(Long.parseLong(sixth.header("Retry-After"))).isBetween(1L, 900L);
        assertThat(events("LOGIN_THROTTLED", null)).isGreaterThanOrEqualTo(1);

        // proof that no hashing happens for a throttled request: with every verification slot taken, an ordinary
        // attempt is refused as busy while a throttled one is still answered 429
        List<Boolean> held = new ArrayList<>();
        try {
            int slots = limiter.availableSlots();
            for (int i = 0; i < slots; i++) held.add(limiter.tryAcquire());
            assertThat(limiter.availableSlots()).isZero();
            assertThat(b.login(u.getEmail(), PASSWORD, token).status()).as("throttled: no slot needed").isEqualTo(429);
            AppUser other = user("busy", PASSWORD);
            Response busy = b.login(other.getEmail(), PASSWORD, token);
            assertThat(busy.status()).as("not throttled but no free verification slot").isEqualTo(503);
            assertThat(busy.body()).isEqualTo("{\"error\":\"SERVER_BUSY\"}");
        } finally {
            for (Boolean got : held) if (got) limiter.release();
        }
        assertThat(limiter.availableSlots()).isEqualTo(3);
        // the window is per email: another account is not affected
        AppUser other = user("unaffected", PASSWORD);
        assertThat(browser().signIn(other.getEmail(), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void anAddressThatTriesManyEmailsIsThrottledToo() {
        HttpTestClient b = browser();
        String token = b.csrf();
        int last = 0;
        for (int i = 1; i <= 31; i++) {
            last = b.login(email("spray"), "whatever-password", token).status();
            if (i <= 30) assertThat(last).as("attempt " + i).isEqualTo(401);
        }
        assertThat(last).as("the 31st attempt from one address in the window").isEqualTo(429);
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountTemporarilyAndALockedAccountLooksLikeAnyFailure() {
        AppUser u = user("lock", PASSWORD);
        for (int i = 1; i <= 3; i++) assertThat(browser().signIn(u.getEmail(), "wrong-password-" + i).status()).isEqualTo(401);
        assertThat(owner.queryForObject("select locked_until > now() from app_user where id = ?", Boolean.class, u.getId())).isTrue();
        assertThat(events("LOGIN_LOCKED", u.getId())).isGreaterThanOrEqualTo(1);
        Response locked = browser().signIn(u.getEmail(), PASSWORD);
        assertThat(locked.status()).as("right password, but locked").isEqualTo(401);
        assertThat(locked.body()).isEqualTo("{\"error\":\"INVALID_CREDENTIALS\"}");
        owner.update("update app_user set locked_until = now() - interval '1 minute' where id = ?", u.getId());
        owner.update("delete from auth_throttle");
        assertThat(browser().signIn(u.getEmail(), PASSWORD).status()).as("the lock has ended").isEqualTo(200);
        assertThat(owner.queryForObject("select failed_login_count from app_user where id = ?", Integer.class, u.getId())).isZero();
    }

    // ---- the security log keeps no secrets ----------------------------------------------------------------------------

    @Test
    void theSecurityLogAndTheSessionStoreContainNoPasswordTokenSessionIdOrStrangersEmail() {
        String password = "log-check-" + UUID.randomUUID();
        AppUser u = user("logcheck", password);
        String stranger = email("stranger-in-log");
        HttpTestClient b = signedIn(u.getEmail(), password);
        String cookie = b.jar.get(SESSION_COOKIE), id = sessionId(b), csrf = b.jar.get(CSRF_COOKIE);
        browser().signIn(stranger, "some-wrong-password");
        browser().signIn(u.getEmail(), "wrong-" + password);
        b.post("/api/v1/auth/logout", null, CSRF_HEADER, csrf);

        assertThat(owner.queryForObject("select count(*) from auth_event where event_type = 'LOGIN_FAILURE' and user_id is null and email_hmac is not null and ip_hmac is not null", Integer.class))
                .as("an unknown email is logged as a keyed hash only").isGreaterThanOrEqualTo(1);
        for (String secret : List.of(password, "wrong-" + password, id, cookie, csrf, stranger, "stranger-in-log")) {
            assertThat(owner.queryForObject("select count(*) from auth_event e where e::text like ?", Integer.class, "%" + secret + "%"))
                    .as("auth_event must not contain: " + secret.substring(0, 8) + "...").isZero();
        }
        assertThat(owner.queryForObject("select count(*) from auth_throttle t where t::text like ?", Integer.class, "%" + stranger + "%")).isZero();
        assertThat(owner.queryForObject("select count(*) from app_user where password_hash like ? or password_hash like ?", Integer.class, "%" + password + "%", "%" + id + "%")).isZero();
    }

    // ---- account changes end sessions --------------------------------------------------------------------------------

    @Test
    void changingThePasswordEndsOtherSessionsAndDisablingEndsAll() {
        AppUser u = user("account", PASSWORD);
        HttpTestClient laptop = signedIn(u.getEmail(), PASSWORD), phone = signedIn(u.getEmail(), PASSWORD);
        assertThat(laptop.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(phone.get("/api/v1/auth/me").status()).isEqualTo(200);

        int ended = accountSecurity.changePassword(u.getId(), "a-new-password-123", sessionId(laptop));
        assertThat(ended).isEqualTo(1);
        assertThat(laptop.get("/api/v1/auth/me").status()).as("the session that made the change survives").isEqualTo(200);
        assertThat(phone.get("/api/v1/auth/me").status()).as("every other session is ended").isEqualTo(401);
        assertThat(browser().signIn(u.getEmail(), PASSWORD).status()).as("the old password").isEqualTo(401);
        assertThat(browser().signIn(u.getEmail(), "a-new-password-123").status()).isEqualTo(200);
        assertThat(events("PASSWORD_CHANGED", u.getId())).isEqualTo(1);
        assertThat(events("SESSIONS_REVOKED", u.getId())).isEqualTo(1);

        int all = accountSecurity.disable(u.getId());
        assertThat(all).isGreaterThanOrEqualTo(2);
        assertThat(laptop.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(browser().signIn(u.getEmail(), "a-new-password-123").status()).as("a disabled account").isEqualTo(401);
        assertThat(events("ACCOUNT_DISABLED", u.getId())).isEqualTo(1);
        accountSecurity.enable(u.getId());
        assertThat(browser().signIn(u.getEmail(), "a-new-password-123").status()).isEqualTo(200);
        assertThat(events("ACCOUNT_ENABLED", u.getId())).isEqualTo(1);
    }

    @Test
    void aPasswordThatIsTooLongCannotBeSetEither() {
        AppUser u = user("toolong", PASSWORD);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accountSecurity.changePassword(u.getId(), "क".repeat(25), null))
                .isInstanceOf(com.mandi.identity.InvalidPasswordException.class);
        assertThat(browser().signIn(u.getEmail(), PASSWORD).status()).as("nothing changed").isEqualTo(200);
    }
}
