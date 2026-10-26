package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;
import com.mandi.identity.AppUser;
import com.mandi.identity.UserAccountService;
import com.mandi.security.HttpTestClient.Response;

/**
 * Two-firm isolation through REAL authenticated HTTP requests and REAL PostgreSQL queries: the embedded server, the real
 * Spring Security chain and server-side sessions, the real membership checks, the real services and repositories, and a
 * database created for this class and migrated by the application's own Flyway migrations. Nothing here stands in for
 * CurrentFirm. Every person, firm and farmer is a synthetic fixture created by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirmContextIsolationIntegrationTest {

    private static final String URL = TestcontainersConfiguration.newDatabaseUrl();
    private static final String PASSWORD = "correct-horse-battery";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String FIRM_CHECK = "X-Mandi-Firm-Check";

    @DynamicPropertySource
    static void disposableDatabase(DynamicPropertyRegistry registry) {
        TestcontainersConfiguration.registerDatabaseProperties(registry, () -> URL);
    }

    @LocalServerPort int port;
    @Autowired UserAccountService accounts;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    /** the OWNER connection of the disposable database: fixtures and assertions only */
    private final JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(URL,
            TestcontainersConfiguration.postgres().getUsername(), TestcontainersConfiguration.postgres().getPassword()));

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    private static boolean fixturesReady;
    private static long f1, f1b, f2, f3;                   // firms
    private static long farmerOne, farmerOneB, farmerTwo, farmerThree;
    private static String o1, o2, o3, e1, e2Revoked, eMulti;   // emails

    private long firm(String name) {
        return owner.queryForObject("insert into firm (code, name) values (?, ?) returning id", Long.class,
                "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), name);
    }

    private long farmer(long firmId, String name) {
        return owner.queryForObject("insert into farmer (name, firm_id) values (?, ?) returning id", Long.class, name, firmId);
    }

    private String person(String tag) {
        AppUser u = accounts.create(tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test", "Synthetic " + tag, PASSWORD);
        return u.getEmail();
    }

    private long userId(String email) {
        return owner.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private void member(long firmId, String email, String role, String status) {
        owner.update("insert into firm_member (firm_id, user_id, role, status, deactivated_at) values (?, ?, ?, ?, case when ? = 'INACTIVE' then now() end)",
                firmId, userId(email), role, status, status);
    }

    /** An ACTIVE commercial account for the owner (the account gate reads it). */
    private void account(String ownerEmail, String status) {
        owner.update("insert into platform.owner_account (display_name, status, owner_user_id, suspended_reason_code) values (?, ?, ?, ?)",
                "Synthetic account of " + ownerEmail, status, userId(ownerEmail), status.equals("SUSPENDED") ? "NON_PAYMENT" : null);
    }

    @BeforeEach
    synchronized void sharedFixtures() {
        if (fixturesReady) return;
        o1 = person("owner-one");
        o2 = person("owner-two");
        o3 = person("owner-three");
        e1 = person("employee-one");
        e2Revoked = person("employee-revoked");
        eMulti = person("employee-multi");
        f1 = firm("Firm One");
        f1b = firm("Firm One B");
        f2 = firm("Firm Two");
        f3 = firm("Firm Three");
        for (String o : List.of(o1, o2, o3)) account(o, "ACTIVE");
        member(f1, o1, "OWNER", "ACTIVE");
        member(f1b, o1, "OWNER", "ACTIVE");
        member(f2, o2, "OWNER", "ACTIVE");
        member(f3, o3, "OWNER", "ACTIVE");
        member(f1, e1, "EMPLOYEE", "ACTIVE");
        member(f1, e2Revoked, "EMPLOYEE", "INACTIVE");
        member(f1, eMulti, "EMPLOYEE", "ACTIVE");
        member(f2, eMulti, "EMPLOYEE", "ACTIVE");
        farmerOne = farmer(f1, "Farmer One");
        farmerOneB = farmer(f1b, "Farmer One B");
        farmerTwo = farmer(f2, "Farmer Two");
        farmerThree = farmer(f3, "Farmer Three");
        fixturesReady = true;
    }

    /** A private firm with its own owner (ACTIVE account), for tests that change state. */
    private record Scenario(long firm, String ownerEmail) {
    }

    private Scenario scenario(String tag) {
        String o = person(tag + "-owner");
        long f = firm("Scenario " + tag);
        account(o, "ACTIVE");
        member(f, o, "OWNER", "ACTIVE");
        farmer(f, "Scenario farmer " + tag);
        return new Scenario(f, o);
    }

    private String employeeOf(long firmId, String tag) {
        String e = person(tag);
        member(firmId, e, "EMPLOYEE", "ACTIVE");
        return e;
    }

    // ---- HTTP helpers ------------------------------------------------------------------------------------------------

    private HttpTestClient signIn(String email) {
        HttpTestClient b = new HttpTestClient(port);
        Response r = b.signIn(email, PASSWORD);
        assertThat(r.status()).as("sign-in of " + email).isEqualTo(200);
        return b;
    }

    private Response select(HttpTestClient b, long firmId) {
        return b.post("/api/v1/auth/active-firm", "{\"firmId\":" + firmId + "}", CSRF_HEADER, b.jar.get(CSRF_COOKIE));
    }

    private Response call(HttpTestClient b, String method, String path, String body, Object firmCheck) {
        List<String> h = new ArrayList<>(List.of(CSRF_HEADER, b.jar.get(CSRF_COOKIE)));
        if (firmCheck != null) h.addAll(List.of(FIRM_CHECK, String.valueOf(firmCheck)));
        return b.send(method, path, body, h.toArray(String[]::new));
    }

    private List<String> farmerNames(HttpTestClient b, long firmCheck) {
        Response r = call(b, "GET", "/api/v1/masters/farmers", null, firmCheck);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$[*].name");
    }

    private Response createFarmer(HttpTestClient b, String name, Object firmCheck) {
        return call(b, "POST", "/api/v1/masters/farmers", "{\"name\":\"" + name + "\",\"nameHi\":null}", firmCheck);
    }

    private static void assertError(Response r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.body()).isEqualTo("{\"error\":\"" + code + "\"}");
    }

    private int farmersNamed(String name) {
        return owner.queryForObject("select count(*) from farmer where name = ?", Integer.class, name);
    }

    // ---- 1. unauthenticated -------------------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsRemainDenied() {
        HttpTestClient anon = new HttpTestClient(port);
        assertError(anon.get("/api/v1/masters/farmers", FIRM_CHECK, String.valueOf(f1)), 401, "UNAUTHENTICATED");
        assertError(anon.get("/api/v1/auth/firms"), 401, "UNAUTHENTICATED");
        String token = anon.csrf();
        assertError(anon.post("/api/v1/auth/active-firm", "{\"firmId\":" + f1 + "}", CSRF_HEADER, token), 401, "UNAUTHENTICATED");
        assertError(anon.post("/api/v1/masters/farmers", "{\"name\":\"Anon\"}", CSRF_HEADER, token, FIRM_CHECK, String.valueOf(f1)), 401, "UNAUTHENTICATED");
        assertThat(farmersNamed("Anon")).isZero();

        HttpTestClient b = signIn(o2);
        assertThat(farmerNames(b, f2)).contains("Farmer Two").doesNotContain("Farmer One", "Farmer One B", "Farmer Three");
        b.post("/api/v1/auth/logout", null, CSRF_HEADER, b.jar.get(CSRF_COOKIE));
        assertError(b.get("/api/v1/masters/farmers", FIRM_CHECK, String.valueOf(f2)), 401, "UNAUTHENTICATED");
    }

    // ---- 2. owners see only their own firms ---------------------------------------------------------------------------

    @Test
    void eachOwnerSeesOnlyTheirOwnFirmsFarmers() {
        HttpTestClient two = signIn(o2);            // one firm: selected automatically
        assertThat(farmerNames(two, f2)).contains("Farmer Two").doesNotContain("Farmer One", "Farmer One B", "Farmer Three");
        assertThat(owner.queryForObject("select count(*) from farmer where firm_id = ?", Integer.class, f2).longValue())
                .isEqualTo(((Number) JsonPath.read(call(two, "GET", "/api/v1/masters/farmers/page", null, f2).body(), "$.totalElements")).longValue());
        Response twoFirms = two.get("/api/v1/auth/firms");
        assertThat((List<Integer>) JsonPath.read(twoFirms.body(), "$.content[*].id")).containsExactly((int) f2);

        HttpTestClient one = signIn(o1);            // two firms: nothing selected until chosen
        assertError(call(one, "GET", "/api/v1/masters/farmers", null, f1), 409, "FIRM_SELECTION_REQUIRED");
        assertThat((List<Integer>) JsonPath.read(one.get("/api/v1/auth/firms").body(), "$.content[*].id")).containsExactlyInAnyOrder((int) f1, (int) f1b);
        assertThat(select(one, f1).status()).isEqualTo(200);
        assertThat(farmerNames(one, f1)).contains("Farmer One").doesNotContain("Farmer One B", "Farmer Two", "Farmer Three");
        assertThat(select(one, f1b).status()).isEqualTo(200);
        assertThat(farmerNames(one, f1b)).contains("Farmer One B").doesNotContain("Farmer One", "Farmer Two", "Farmer Three");
        // owner one can never choose owner two's or owner three's firm
        assertError(select(one, f2), 403, "NO_MEMBERSHIP");
        assertError(select(one, f3), 403, "NO_MEMBERSHIP");
        assertThat(farmerNames(one, f1b)).as("the failed selections changed nothing").contains("Farmer One B").doesNotContain("Farmer Two");
        // the search of the firm list is scoped to the person
        assertThat((List<Integer>) JsonPath.read(one.get("/api/v1/auth/firms?q=two").body(), "$.content[*].id")).isEmpty();
        assertThat((List<Integer>) JsonPath.read(one.get("/api/v1/auth/firms?q=one%20b").body(), "$.content[*].id")).containsExactly((int) f1b);
    }

    // ---- 3. employees: only the assigned firm(s) -----------------------------------------------------------------------

    @Test
    void anEmployeeCanOnlyUseTheFirmsTheyAreAssignedTo() {
        HttpTestClient e = signIn(e1);               // assigned to Firm One only: selected automatically
        assertThat(farmerNames(e, f1)).contains("Farmer One").doesNotContain("Farmer One B", "Farmer Two", "Farmer Three");
        assertThat((List<String>) JsonPath.read(e.get("/api/v1/auth/firms").body(), "$.content[*].role")).containsExactly("EMPLOYEE");
        Response toTwo = select(e, f2);
        Response toNowhere = select(e, 999_999);
        assertError(toTwo, 403, "NO_MEMBERSHIP");
        assertThat(toNowhere.body()).as("a firm that does not exist answers like a firm without membership").isEqualTo(toTwo.body());
        assertThat(toNowhere.status()).isEqualTo(403);
        // guessing another firm's number in the check header does not work either: the session's firm is the one that counts
        assertError(call(e, "GET", "/api/v1/masters/farmers", null, f2), 409, "FIRM_MISMATCH");
        assertThat(createFarmer(e, "Made By Employee One", f1).status()).isEqualTo(201);
        assertThat(owner.queryForObject("select firm_id from farmer where name = 'Made By Employee One'", Long.class)).isEqualTo(f1);
        assertThat(owner.queryForObject("select actor_role || '/' || actor_user_id from audit_event where entity_type = 'FARMER' and details like '%Made By Employee One%'", String.class))
                .as("the role AT THE TIME of the action is recorded").isEqualTo("EMPLOYEE/" + userId(e1));

        // an employee assigned to two firms sees each only after choosing it, and only those two
        HttpTestClient m = signIn(eMulti);
        assertError(call(m, "GET", "/api/v1/masters/farmers", null, f1), 409, "FIRM_SELECTION_REQUIRED");
        assertThat(select(m, f2).status()).isEqualTo(200);
        assertThat(farmerNames(m, f2)).contains("Farmer Two").doesNotContain("Farmer One", "Farmer One B");
        assertThat(select(m, f1).status()).isEqualTo(200);
        assertThat(farmerNames(m, f1)).contains("Farmer One").doesNotContain("Farmer Two");
        assertError(select(m, f3), 403, "NO_MEMBERSHIP");
    }

    // ---- 4. another firm's ids ------------------------------------------------------------------------------------------

    @Test
    void anotherFirmsIdsCannotBeReadOrModifiedAndTheClientCannotChooseTheFirm() {
        // fixtures of firm Two that an outsider will try to reach: a bill and an audit event
        long billTwo = owner.queryForObject("insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id) "
                + "values ('2026-10-01', ?, 'NOT_CALCULATED', 'NONE', ?) returning id", Long.class, farmerTwo, f2);
        owner.update("insert into audit_event (entity_type, entity_id, action, details, actor, firm_id) values ('FARMER', ?, 'CREATE', 'secret detail of firm two', 'anonymous', ?)", farmerTwo, f2);

        HttpTestClient b = signIn(o1);
        assertThat(select(b, f1).status()).isEqualTo(200);

        // by id, in the path
        Response put = call(b, "PUT", "/api/v1/masters/farmers/" + farmerTwo, "{\"name\":\"Hijacked\"}", f1);
        Response patch = call(b, "PATCH", "/api/v1/masters/farmers/" + farmerTwo + "/active", "{\"active\":false}", f1);
        Response missing = call(b, "PUT", "/api/v1/masters/farmers/987654321", "{\"name\":\"Nobody\"}", f1);
        assertThat(put.status()).isEqualTo(404);
        assertThat(patch.status()).isEqualTo(404);
        assertThat((Object) JsonPath.read(put.body(), "$.detail")).as("a foreign id looks exactly like a missing one")
                .isEqualTo(JsonPath.read(missing.body(), "$.detail"));
        assertThat((Object) JsonPath.read(put.body(), "$.title")).isEqualTo(JsonPath.read(missing.body(), "$.title"));
        assertThat(owner.queryForObject("select name from farmer where id = ?", String.class, farmerTwo)).isEqualTo("Farmer Two");
        assertThat(owner.queryForObject("select active from farmer where id = ?", Boolean.class, farmerTwo)).isTrue();
        assertThat(call(b, "GET", "/api/v1/aadat/bills/" + billTwo, null, f1).status()).isEqualTo(404);
        assertThat((Object) JsonPath.read(call(b, "GET", "/api/v1/aadat/bills?includeDemo=true", null, f1).body(), "$.totalElements")).isEqualTo(0);
        assertThat((List<?>) JsonPath.read(call(b, "GET", "/api/v1/audit?entityType=FARMER&entityId=" + farmerTwo, null, f1).body(), "$")).isEmpty();

        // in the query, in a header, in the body: ignored, the session's firm decides
        Response viaQuery = b.send("GET", "/api/v1/masters/farmers?firmId=" + f2, null, CSRF_HEADER, b.jar.get(CSRF_COOKIE), FIRM_CHECK, String.valueOf(f1),
                "X-Firm-Id", String.valueOf(f2), "X-Firm", String.valueOf(f2));
        assertThat((List<String>) JsonPath.read(viaQuery.body(), "$[*].name")).contains("Farmer One").doesNotContain("Farmer Two");
        Response created = call(b, "POST", "/api/v1/masters/farmers", "{\"name\":\"Sneaky Farmer\",\"firmId\":" + f2 + ",\"firm_id\":" + f2 + "}", f1);
        assertThat(created.status()).isEqualTo(201);
        assertThat(owner.queryForObject("select firm_id from farmer where name = 'Sneaky Farmer'", Long.class)).as("stored in the session's firm").isEqualTo(f1);
        long createdId = ((Number) JsonPath.read(created.body(), "$.id")).longValue();
        assertThat(owner.queryForObject("select firm_id from audit_event where entity_type = 'FARMER' and entity_id = ? and action = 'CREATED'", Long.class,
                createdId)).as("the audit event carries the same firm").isEqualTo(f1);
        Map<String, Object> event = owner.queryForMap("select actor, actor_user_id, actor_membership_id, actor_role, request_id::text as request_id from audit_event "
                + "where entity_type = 'FARMER' and entity_id = ? and action = 'CREATED'", createdId);
        assertThat(event.get("actor_user_id")).as("who: the signed-in owner, from the session").isEqualTo(userId(o1));
        assertThat(event.get("actor")).isEqualTo("user:" + userId(o1));
        assertThat(event.get("actor_role")).isEqualTo("OWNER");
        assertThat(event.get("actor_membership_id")).isEqualTo(owner.queryForObject("select id from firm_member where firm_id = ? and user_id = ?", Long.class, f1, userId(o1)));
        assertThat(event.get("request_id")).as("the id the server returned for that request").isEqualTo(created.header("X-Request-Id"));

        // a foreign id INSIDE a request is refused by the application and by the database (composite foreign keys)
        long item = ((Number) JsonPath.read(call(b, "POST", "/api/v1/masters/items", "{\"name\":\"Isolation Item\",\"nameHi\":null}", f1).body(), "$.id")).longValue();
        String bill = "{\"billDate\":\"2026-10-17\",\"farmerId\":" + farmerTwo + ",\"villageId\":null,\"lots\":[{\"itemId\":" + item
                + ",\"pieces\":1,\"weight\":5,\"rate\":2,\"buyerId\":null}],\"expenses\":[]}";
        Response foreign = call(b, "POST", "/api/v1/aadat/bills", bill, f1);
        assertThat(foreign.status()).as(foreign.body()).isIn(400, 404, 422);
        assertThat(owner.queryForObject("select count(*) from aadat_bill where firm_id = ? and farmer_id = ?", Integer.class, f1, farmerTwo)).isZero();
        assertThat(owner.queryForObject("select count(*) from aadat_bill where farmer_id = ? and firm_id <> ?", Integer.class, farmerTwo, f2)).isZero();
    }

    // ---- 5. stale firm context ------------------------------------------------------------------------------------------

    @Test
    void aStaleFirmContextIsRejectedInsteadOfSilentlyMovingAWrite() {
        HttpTestClient session = signIn(o1);                         // one browser session, two tabs
        assertThat(select(session, f1).status()).isEqualTo(200);      // tab A works in Firm One
        assertThat(farmerNames(session, f1)).contains("Farmer One").doesNotContain("Farmer One B");

        assertThat(select(session, f1b).status()).as("tab B switches the SESSION to Firm One B").isEqualTo(200);

        // tab A still believes it is in Firm One
        assertError(createFarmer(session, "Stale Write", f1), 409, "FIRM_MISMATCH");
        assertError(call(session, "GET", "/api/v1/masters/farmers", null, f1), 409, "FIRM_MISMATCH");
        assertError(call(session, "PUT", "/api/v1/masters/farmers/" + farmerOne, "{\"name\":\"Stale Edit\"}", f1), 409, "FIRM_MISMATCH");
        assertThat(farmersNamed("Stale Write")).as("not written to either firm").isZero();
        assertThat(owner.queryForObject("select name from farmer where id = ?", String.class, farmerOne)).isEqualTo("Farmer One");
        // missing or garbage check header
        assertError(createFarmer(session, "No Check", null), 409, "FIRM_CHECK_REQUIRED");
        assertError(createFarmer(session, "Bad Check", "abc"), 409, "FIRM_MISMATCH");
        assertError(createFarmer(session, "Foreign Check", f2), 409, "FIRM_MISMATCH");
        assertThat(farmersNamed("No Check") + farmersNamed("Bad Check") + farmersNamed("Foreign Check")).isZero();
        // tab A reloads and now knows Firm One B
        assertThat(createFarmer(session, "Fresh Write", f1b).status()).isEqualTo(201);
        assertThat(owner.queryForObject("select firm_id from farmer where name = 'Fresh Write'", Long.class)).isEqualTo(f1b);

        // a new sign-in starts with no firm selected (the choice does not outlive the session)
        HttpTestClient again = signIn(o1);
        assertError(call(again, "GET", "/api/v1/masters/farmers", null, f1b), 409, "FIRM_SELECTION_REQUIRED");
    }

    // ---- 6. revoked membership ------------------------------------------------------------------------------------------

    @Test
    void revokingAMembershipStopsTheVeryNextRequestOfALiveSession() {
        Scenario s = scenario("revoke");
        String emp = employeeOf(s.firm(), "revoked-soon");
        HttpTestClient b = signIn(emp);
        assertThat(farmerNames(b, s.firm())).hasSize(1);
        assertThat(createFarmer(b, "Before Revocation", s.firm()).status()).isEqualTo(201);

        owner.update("update firm_member set status = 'INACTIVE', deactivated_at = now() where firm_id = ? and user_id = ?", s.firm(), userId(emp));

        assertError(call(b, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "NO_MEMBERSHIP");
        assertError(createFarmer(b, "After Revocation", s.firm()), 403, "NO_MEMBERSHIP");
        assertThat(farmersNamed("After Revocation")).isZero();
        assertError(select(b, s.firm()), 403, "NO_MEMBERSHIP");
        assertThat(b.get("/api/v1/auth/me").status()).as("the person is still signed in; only the firm is closed to them").isEqualTo(200);

        owner.update("update firm_member set status = 'ACTIVE', deactivated_at = null where firm_id = ? and user_id = ?", s.firm(), userId(emp));
        assertThat(farmerNames(b, s.firm())).as("reactivation applies at once too").contains("Before Revocation");

        // a person whose membership was revoked before they ever signed in
        HttpTestClient r = signIn(e2Revoked);
        assertError(call(r, "GET", "/api/v1/masters/farmers", null, f1), 409, "FIRM_SELECTION_REQUIRED");
        assertError(select(r, f1), 403, "NO_MEMBERSHIP");
        assertThat((List<?>) JsonPath.read(r.get("/api/v1/auth/firms").body(), "$.content")).isEmpty();
    }

    // ---- 7. suspended or lapsed accounts ---------------------------------------------------------------------------------

    @Test
    void aSuspendedOrLapsedOwnerAccountBlocksTheOwnerAndTheEmployeesOnTheNextRequest() {
        Scenario s = scenario("suspend");
        String emp = employeeOf(s.firm(), "of-suspended");
        HttpTestClient ob = signIn(s.ownerEmail()), eb = signIn(emp);
        assertThat(farmerNames(ob, s.firm())).isNotEmpty();
        assertThat(farmerNames(eb, s.firm())).isNotEmpty();
        String account = "update platform.owner_account set status = ?, suspended_reason_code = ?, trial_started_at = ?, trial_ends_at = ?, paid_until = ?, access_grace_until = ? where owner_user_id = ?";
        java.sql.Timestamp past = java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), pastEnd = java.sql.Timestamp.valueOf("2020-01-15 00:00:00");
        java.sql.Timestamp future = java.sql.Timestamp.valueOf("2999-01-01 00:00:00");
        long ownerId = userId(s.ownerEmail());

        owner.update(account, "SUSPENDED", "NON_PAYMENT", null, null, null, null, ownerId);
        for (HttpTestClient who : List.of(ob, eb)) {
            assertError(call(who, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "ACCOUNT_BLOCKED");
            assertError(createFarmer(who, "Suspended Write", s.firm()), 403, "ACCOUNT_BLOCKED");
            assertThat(who.get("/api/v1/auth/me").status()).as("sign-in and the account page still work").isEqualTo(200);
        }
        assertThat(farmersNamed("Suspended Write")).isZero();

        owner.update(account, "ACTIVE", null, null, null, null, null, ownerId);                       // reinstated
        assertThat(farmerNames(ob, s.firm())).isNotEmpty();
        assertThat(farmerNames(eb, s.firm())).isNotEmpty();

        owner.update(account, "TRIAL", null, past, pastEnd, null, null, ownerId);                       // trial expired (derived from dates)
        assertError(call(eb, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "ACCOUNT_BLOCKED");
        owner.update(account, "TRIAL", null, past, pastEnd, null, future, ownerId);                     // a manual grace date
        assertThat(farmerNames(eb, s.firm())).isNotEmpty();
        owner.update(account, "ACTIVE", null, null, null, pastEnd, null, ownerId);                      // paid period lapsed
        assertError(call(ob, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "ACCOUNT_BLOCKED");
        owner.update(account, "ACTIVE", null, null, null, future, null, ownerId);
        assertThat(farmerNames(ob, s.firm())).isNotEmpty();

        // a firm whose owner has no account at all fails closed
        owner.update("update platform.owner_account set owner_user_id = null where owner_user_id = ?", ownerId);
        assertError(call(eb, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "ACCOUNT_BLOCKED");
    }

    @Test
    void anEmployeeOfTwoOwnersIsBlockedOnlyInTheFirmWhoseOwnerIsSuspended() {
        Scenario a = scenario("owner-a"), b = scenario("owner-b");
        String emp = employeeOf(a.firm(), "two-owners");
        member(b.firm(), emp, "EMPLOYEE", "ACTIVE");
        HttpTestClient session = signIn(emp);
        assertThat(select(session, a.firm()).status()).isEqualTo(200);
        assertThat(farmerNames(session, a.firm())).isNotEmpty();
        owner.update("update platform.owner_account set status = 'SUSPENDED', suspended_reason_code = 'NON_PAYMENT' where owner_user_id = ?", userId(a.ownerEmail()));
        assertError(call(session, "GET", "/api/v1/masters/farmers", null, a.firm()), 403, "ACCOUNT_BLOCKED");
        assertThat(select(session, b.firm()).status()).as("the other owner's firm keeps working").isEqualTo(200);
        assertThat(farmerNames(session, b.firm())).isNotEmpty();
    }

    @Test
    void anInactiveFirmAndADisabledPersonAreRefusedOnTheNextRequest() {
        Scenario s = scenario("inactive");
        HttpTestClient b = signIn(s.ownerEmail());
        assertThat(farmerNames(b, s.firm())).isNotEmpty();
        owner.update("update firm set active = false where id = ?", s.firm());
        assertError(call(b, "GET", "/api/v1/masters/farmers", null, s.firm()), 403, "FIRM_INACTIVE");
        assertError(select(b, s.firm()), 403, "NO_MEMBERSHIP");
        assertThat((List<?>) JsonPath.read(b.get("/api/v1/auth/firms").body(), "$.content")).as("an inactive firm is not offered").isEmpty();
        owner.update("update firm set active = true where id = ?", s.firm());
        assertThat(farmerNames(b, s.firm())).isNotEmpty();

        owner.update("update app_user set status = 'DISABLED' where id = ?", userId(s.ownerEmail()));
        assertError(call(b, "GET", "/api/v1/masters/farmers", null, s.firm()), 401, "UNAUTHENTICATED");
        assertThat(b.get("/api/v1/auth/me").status()).as("the session was ended").isEqualTo(401);
    }

    // ---- 8. the common mechanism covers every business route --------------------------------------------------------------

    @Test
    void everyBusinessRouteIsGuardedByTheSameFirmCheck() {
        List<String[]> routes = new ArrayList<>();      // verb, concrete path
        for (Map.Entry<RequestMappingInfo, ?> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            List<String> verbs = methods.isEmpty() ? List.of("GET") : methods.stream().map(Enum::name).toList();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/v1/auth/") || pattern.startsWith("/api/v1/dev/")) continue;
                for (String verb : verbs) routes.add(new String[] {verb, pattern.replace("{type}", "farmers").replaceAll("\\{[^}]+}", "1")});
            }
        }
        assertThat(routes).as("business routes found").hasSizeGreaterThanOrEqualTo(10);

        HttpTestClient b = signIn(o1);                  // two firms, none selected yet
        for (String[] r : routes) {
            assertError(call(b, r[0], r[1], r[0].equals("GET") ? null : "{}", f1), 409, "FIRM_SELECTION_REQUIRED");
        }
        assertThat(select(b, f1).status()).isEqualTo(200);
        for (String[] r : routes) {
            String body = r[0].equals("GET") ? null : "{}";
            assertError(call(b, r[0], r[1], body, null), 409, "FIRM_CHECK_REQUIRED");
            assertError(call(b, r[0], r[1], body, f1b), 409, "FIRM_MISMATCH");
            int status = call(b, r[0], r[1], body, f1).status();
            assertThat(status).as(r[0] + " " + r[1] + " reaches the controller with a correct context").isNotIn(401, 403, 409);
        }
    }

    // ---- 9. the platform database boundary is intact in this database ------------------------------------------------------

    @Test
    void thePlatformRoleStillCannotReachAnyBusinessOrIdentityData() {
        JdbcTemplate platform = new JdbcTemplate(TestcontainersConfiguration.platformDataSource(URL));
        for (String t : List.of("firm", "farmer", "firm_member", "app_user", "audit_event", "auth_event", "spring_session", "credential_token", "aadat_bill")) {
            Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> platform.queryForObject("select count(*) from public." + t, Integer.class));
            assertThat(e).as(t).isNotNull();
            assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e).getMessage()).as(t).contains("permission denied");
        }
        // and what it may read, the owner summary, lists owners only: no employee, no firm
        List<String> emails = platform.queryForList("select email from platform.owner_user_summary", String.class);
        assertThat(emails).contains(o1, o2, o3).doesNotContain(e1, e2Revoked, eMulti);
        // the business role cannot touch the platform schema
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(URL, TestcontainersConfiguration.RUNTIME_ROLE, TestcontainersConfiguration.RUNTIME_PASSWORD));
        Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> app.queryForObject("select count(*) from platform.owner_account", Integer.class));
        assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains("permission denied");
    }

    // ---- 10. the bill workflow (V11) over real sessions: firm settings are owner-only, numbers and bills are per firm -------------

    private String billJson(long farmerId, long itemId) {
        return "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmerId + ",\"lots\":[{\"itemId\":" + itemId
                + ",\"pieces\":2,\"weight\":2,\"rate\":10,\"purchasers\":[]}],\"expenses\":[]}";
    }

    @Test
    void firmSettingsAreForTheOwnerOnlyAndAnEmployeeCanStillEnterBills() {
        Scenario s = scenario("settings");
        String emp = employeeOf(s.firm(), "settings-employee");
        HttpTestClient e = signIn(emp);
        assertThat(select(e, s.firm()).status()).isEqualTo(200);
        assertThat(call(e, "PUT", "/api/v1/firm/profile", "{\"address\":\"x\"}", s.firm()).status()).as("employee cannot edit the print profile").isEqualTo(403);
        assertThat(call(e, "PUT", "/api/v1/firm/bill-numbering", "{\"nextNumber\":500}", s.firm()).status()).as("employee cannot move the series").isEqualTo(403);
        assertThat(call(e, "GET", "/api/v1/firm/profile", null, s.firm()).status()).as("but can read it").isEqualTo(200);
        assertThat(owner.queryForObject("select count(*) from bill_number_counter where firm_id = ?", Integer.class, s.firm())).isZero();

        long item = Long.parseLong(JsonPath.read(call(e, "POST", "/api/v1/masters/items", "{\"name\":\"Settings Item\"}", s.firm()).body(), "$.id").toString());
        long farmerId = owner.queryForObject("select id from farmer where firm_id = ?", Long.class, s.firm());
        Response bill = call(e, "POST", "/api/v1/aadat/bills", billJson(farmerId, item), s.firm());
        assertThat(bill.status()).as(bill.body()).isEqualTo(201);

        HttpTestClient o = signIn(s.ownerEmail());
        assertThat(select(o, s.firm()).status()).isEqualTo(200);
        assertThat(call(o, "PUT", "/api/v1/firm/bill-numbering", "{\"nextNumber\":500}", s.firm()).status()).as("a numbered bill exists: locked").isEqualTo(409);
        Response profile = call(o, "PUT", "/api/v1/firm/profile", "{\"address\":\"Owner Road\",\"licenceNo\":\"L-77\"}", s.firm());
        assertThat(profile.status()).as(profile.body()).isEqualTo(200);
        assertThat(owner.queryForObject("select licence_no from firm where id = ?", String.class, s.firm())).isEqualTo("L-77");
        // the actor of the profile change is the owner of that session
        assertThat(owner.queryForObject("select actor_role from audit_event where entity_type = 'FIRM' and entity_id = ? and action = 'PROFILE_UPDATED'", String.class, s.firm())).isEqualTo("OWNER");
    }

    @Test
    void eachFirmHasItsOwnBillSeriesAndCannotSeeAnotherFirmsBills() {
        Scenario a = scenario("series-a");
        Scenario b = scenario("series-b");
        HttpTestClient oa = signIn(a.ownerEmail());
        HttpTestClient ob = signIn(b.ownerEmail());
        assertThat(select(oa, a.firm()).status()).isEqualTo(200);
        assertThat(select(ob, b.firm()).status()).isEqualTo(200);
        long itemA = Long.parseLong(JsonPath.read(call(oa, "POST", "/api/v1/masters/items", "{\"name\":\"Series Item\"}", a.firm()).body(), "$.id").toString());
        long itemB = Long.parseLong(JsonPath.read(call(ob, "POST", "/api/v1/masters/items", "{\"name\":\"Series Item\"}", b.firm()).body(), "$.id").toString());
        long farmerA = owner.queryForObject("select id from farmer where firm_id = ?", Long.class, a.firm());
        long farmerB = owner.queryForObject("select id from farmer where firm_id = ?", Long.class, b.firm());

        Response a1 = call(oa, "POST", "/api/v1/aadat/bills", billJson(farmerA, itemA), a.firm());
        Response a2 = call(oa, "POST", "/api/v1/aadat/bills", billJson(farmerA, itemA), a.firm());
        Response b1 = call(ob, "POST", "/api/v1/aadat/bills", billJson(farmerB, itemB), b.firm());
        assertThat((Integer) JsonPath.read(a1.body(), "$.billNo")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(a2.body(), "$.billNo")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(b1.body(), "$.billNo")).as("the other firm has its own series").isEqualTo(1);

        long billOfA = ((Number) JsonPath.read(a1.body(), "$.id")).longValue();
        assertThat(call(ob, "GET", "/api/v1/aadat/bills/" + billOfA, null, b.firm()).status()).as("another firm's bill does not exist for B").isEqualTo(404);
        Response search = call(ob, "GET", "/api/v1/aadat/bills?billNo=2", null, b.firm());
        assertThat((Integer) JsonPath.read(search.body(), "$.content.length()")).as("B has no bill number 2").isZero();
        assertThat(call(ob, "GET", "/api/v1/aadat/bills/next-number", null, b.firm()).body()).contains("\"expectedNext\":2");
        assertThat(call(oa, "GET", "/api/v1/aadat/bills/next-number", null, a.firm()).body()).contains("\"expectedNext\":3");
        // a purchaser of another firm cannot be put on a bill
        long buyerB = Long.parseLong(JsonPath.read(call(ob, "POST", "/api/v1/masters/buyers", "{\"name\":\"B Buyer\"}", b.firm()).body(), "$.id").toString());
        String foreign = "{\"billDate\":\"2026-10-24\",\"farmerId\":" + farmerA + ",\"lots\":[{\"itemId\":" + itemA + ",\"pieces\":1,\"weight\":1,\"rate\":1,\"purchasers\":[{\"buyerId\":" + buyerB + ",\"pieces\":1,\"weight\":1,\"rate\":1}]}],\"expenses\":[]}";
        assertThat(call(oa, "POST", "/api/v1/aadat/bills", foreign, a.firm()).status()).as("a purchaser of another firm").isEqualTo(422);
        assertThat(call(oa, "GET", "/api/v1/aadat/bills/next-number", null, a.firm()).body()).as("the refused save took no number").contains("\"expectedNext\":3");
    }
}
