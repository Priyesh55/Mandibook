package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.mandi.TestcontainersConfiguration;
import com.mandi.identity.AppUser;
import com.mandi.identity.OneTimeTokens;
import com.mandi.identity.UserAccountService;
import com.mandi.security.HttpTestClient.Response;

/**
 * How people come into the system, over real HTTP on a disposable database: an owner invited from the platform side, the
 * owner's firm, the owner's employees, and everything that must NOT be possible (self-registration, choosing a role or
 * another firm, using somebody else's invitation). All people and tokens are synthetic. The platform-side step (creating the
 * owner account and invitation) is done with the database-owner connection of the test, exactly what the development
 * runbook does; the invited person's side goes through the real endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mandi.security.login.max-attempts-per-ip=1000")
class OnboardingIntegrationTest {

    private static final String URL = TestcontainersConfiguration.newDatabaseUrl();
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String FIRM_CHECK = "X-Mandi-Firm-Check";
    private static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void disposableDatabase(DynamicPropertyRegistry registry) {
        TestcontainersConfiguration.registerDatabaseProperties(registry, () -> URL);
    }

    @LocalServerPort int port;
    @Autowired UserAccountService accounts;
    private final JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(URL,
            TestcontainersConfiguration.postgres().getUsername(), TestcontainersConfiguration.postgres().getPassword()));

    // ---- the platform side (what the development runbook does as the database owner) ----------------------------------------

    private record Invitation(long accountId, String email, String token) {
    }

    private Invitation ownerInvitation(String tag, String status) {
        String email = tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        long account = owner.queryForObject("insert into platform.owner_account (display_name, status) values (?, ?) returning id", Long.class, "Org of " + tag, status);
        OneTimeTokens.Issued t = OneTimeTokens.issue();
        owner.update("insert into platform.owner_invitation (owner_account_id, email, token_hash, expires_at) values (?, ?, ?, now() + interval '7 days')", account, email, t.hash());
        return new Invitation(account, email, t.raw());
    }

    // ---- the person's side ----------------------------------------------------------------------------------------------------

    private HttpTestClient browser() {
        return new HttpTestClient(port);
    }

    private Response accept(HttpTestClient b, String token, String password, String name) {
        String csrf = b.jar.containsKey(CSRF_COOKIE) ? b.jar.get(CSRF_COOKIE) : b.csrf();
        String body = "{\"token\":" + HttpTestClient.quote(token) + ",\"password\":" + HttpTestClient.quote(password) + ",\"displayName\":" + HttpTestClient.quote(name) + "}";
        return b.post("/api/v1/auth/invitations/accept", body, CSRF_HEADER, csrf);
    }

    private HttpTestClient signIn(String email, String password) {
        HttpTestClient b = browser();
        assertThat(b.signIn(email, password).status()).as("sign-in of " + email).isEqualTo(200);
        return b;
    }

    private Response post(HttpTestClient b, String path, String body, Object firmCheck) {
        return b.send("POST", path, body, firmCheck == null ? new String[] {CSRF_HEADER, b.jar.get(CSRF_COOKIE)}
                : new String[] {CSRF_HEADER, b.jar.get(CSRF_COOKIE), FIRM_CHECK, String.valueOf(firmCheck)});
    }

    private Response get(HttpTestClient b, String path, Object firmCheck) {
        return b.send("GET", path, null, firmCheck == null ? new String[] {} : new String[] {FIRM_CHECK, String.valueOf(firmCheck)});
    }

    /** Owner account invited, invitation accepted, signed in. */
    private record Owner(String email, HttpTestClient session) {
    }

    private Owner newOwner(String tag) {
        Invitation inv = ownerInvitation(tag, "ACTIVE");
        HttpTestClient b = browser();
        assertThat(accept(b, inv.token(), PASSWORD, "Owner " + tag).status()).isEqualTo(200);
        return new Owner(inv.email(), signIn(inv.email(), PASSWORD));
    }

    private long createFirm(Owner o, String name) {
        Response r = post(o.session(), "/api/v1/auth/firms", "{\"name\":" + HttpTestClient.quote(name) + ",\"nameHi\":null}", null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return ((Number) r.json("$.id")).longValue();
    }

    private void select(HttpTestClient b, long firm) {
        assertThat(post(b, "/api/v1/auth/active-firm", "{\"firmId\":" + firm + "}", null).status()).isEqualTo(200);
    }

    /** the link of the most recent invitation mail for an address, as a developer would read it from the sink */
    private String tokenMailedTo(String email) {
        List<Map<String, Object>> mail = JsonPath.read(browser().get("/api/v1/dev/mail").body(), "$");
        for (Map<String, Object> m : mail) {
            if (email.equals(m.get("to"))) {
                String link = (String) m.get("link");
                assertThat(link).contains("/accept-invitation#token=");
                return link.substring(link.indexOf("#token=") + 7);
            }
        }
        throw new AssertionError("no mail for " + email);
    }

    private long userId(String email) {
        return owner.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    // ---- 1. an owner from invitation to a working firm -----------------------------------------------------------------------------

    @Test
    void anInvitedOwnerSetsAPasswordSignsInCreatesAFirmAndUsesIt() {
        Invitation inv = ownerInvitation("fresh-owner", "TRIAL");
        HttpTestClient b = browser();
        assertThat(accept(b, inv.token(), "short", "x").body()).contains("INVALID_PASSWORD").contains("TOO_SHORT");
        assertThat(accept(b, inv.token(), "क".repeat(25), "x").body()).as("75 bytes").contains("TOO_LONG_BYTES");
        assertThat(owner.queryForObject("select count(*) from app_user where email = ?", Integer.class, inv.email())).as("a refused password creates nothing").isZero();
        assertThat(owner.queryForObject("select accepted_at is null from platform.owner_invitation where owner_account_id = ?", Boolean.class, inv.accountId())).isTrue();

        Response ok = accept(b, inv.token(), PASSWORD, "Fresh Owner");
        assertThat(ok.status()).isEqualTo(200);
        assertThat((Object) ok.json("$.kind")).isEqualTo("OWNER");
        assertThat(ok.body()).doesNotContain(PASSWORD).doesNotContain(inv.token());
        assertThat(owner.queryForObject("select email_verified_at is not null from app_user where email = ?", Boolean.class, inv.email())).isTrue();
        Map<String, Object> account = owner.queryForMap("select owner_user_id, trial_started_at, trial_ends_at, status from platform.owner_account where id = ?", inv.accountId());
        assertThat(account.get("owner_user_id")).isEqualTo(userId(inv.email()));
        assertThat(((Timestamp) account.get("trial_ends_at")).getTime() - ((Timestamp) account.get("trial_started_at")).getTime())
                .as("a 14-day trial starts when the invitation is accepted").isEqualTo(14L * 24 * 3600 * 1000);
        assertThat(owner.queryForObject("select password_hash like '{bcrypt}%' and password_hash not like ? from app_user where email = ?", Boolean.class, "%" + PASSWORD + "%", inv.email())).isTrue();

        Response again = accept(b, inv.token(), PASSWORD, "Second try");
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).isEqualTo("{\"error\":\"INVITATION_INVALID\"}");

        HttpTestClient session = signIn(inv.email(), PASSWORD);
        Response noFirm = get(session, "/api/v1/masters/farmers", 1);
        assertThat(noFirm.body()).as("no firm yet").contains("FIRM_SELECTION_REQUIRED");
        long firm = createFirm(new Owner(inv.email(), session), "Fresh Firm");
        assertThat(owner.queryForObject("select code ~ '^F[0-9A-F]{12}$' from firm where id = ?", Boolean.class, firm)).as("the code is generated, not chosen").isTrue();
        assertThat(owner.queryForList("select name from item where firm_id = ? order by name", String.class, firm)).containsExactly("Garlic", "Onion", "Potato");
        assertThat(owner.queryForObject("select role from firm_member where firm_id = ? and user_id = ?", String.class, firm, userId(inv.email()))).isEqualTo("OWNER");
        assertThat(owner.queryForObject("select actor from audit_event where entity_type = 'FIRM' and entity_id = ?", String.class, firm)).isEqualTo("user:" + userId(inv.email()));
        select(session, firm);
        Response items = get(session, "/api/v1/masters/items", firm);
        assertThat((List<String>) JsonPath.read(items.body(), "$[*].name")).containsExactlyInAnyOrder("Onion", "Potato", "Garlic");
        // an owner may have any number of firms
        long second = createFirm(new Owner(inv.email(), session), "Second Firm");
        assertThat(second).isNotEqualTo(firm);
        assertThat(((Number) JsonPath.read(session.get("/api/v1/auth/firms").body(), "$.totalElements")).intValue()).isEqualTo(2);
    }

    @Test
    void invitationTokensThatAreUnknownMalformedExpiredRevokedOrForAnExistingAccountAreRefused() {
        HttpTestClient b = browser();
        String unknown = OneTimeTokens.issue().raw();
        Response r1 = accept(b, unknown, PASSWORD, "x");
        Response r2 = accept(b, "not-a-token", PASSWORD, "x");
        assertThat(r1.status()).isEqualTo(400);
        assertThat(r1.body()).isEqualTo("{\"error\":\"INVITATION_INVALID\"}");
        assertThat(r2.body()).isEqualTo(r1.body());

        Invitation expired = ownerInvitation("expired", "ACTIVE");
        owner.update("update platform.owner_invitation set created_at = now() - interval '10 days', expires_at = now() - interval '3 days' where owner_account_id = ?", expired.accountId());
        assertThat(accept(b, expired.token(), PASSWORD, "x").body()).isEqualTo(r1.body());

        Invitation revoked = ownerInvitation("revoked", "ACTIVE");
        owner.update("update platform.owner_invitation set revoked_at = now() where owner_account_id = ?", revoked.accountId());
        assertThat(accept(b, revoked.token(), PASSWORD, "x").body()).isEqualTo(r1.body());

        Invitation taken = ownerInvitation("taken", "ACTIVE");
        AppUser existing = accounts.create(taken.email(), "Existing", PASSWORD);
        Response r = accept(b, taken.token(), PASSWORD, "x");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).isEqualTo("{\"error\":\"ACCOUNT_EXISTS\"}");
        assertThat(owner.queryForObject("select owner_user_id is null from platform.owner_account where id = ?", Boolean.class, taken.accountId())).isTrue();
        assertThat(existing.getId()).isNotNull();
        assertThat(owner.queryForObject("select count(*) from app_user where email like 'expired-%' or email like 'revoked-%'", Integer.class)).isZero();
    }

    @Test
    void aSuspendedOwnerAccountCannotCreateAFirmAndAPlainUserIsNotAnOwner() {
        Owner o = newOwner("suspended-owner");
        owner.update("update platform.owner_account set status = 'SUSPENDED', suspended_reason_code = 'NON_PAYMENT' where owner_user_id = ?", userId(o.email()));
        Response blocked = post(o.session(), "/api/v1/auth/firms", "{\"name\":\"Blocked Firm\"}", null);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.body()).isEqualTo("{\"error\":\"ACCOUNT_BLOCKED\"}");

        String plain = "plain-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        accounts.create(plain, "Plain user", PASSWORD);
        HttpTestClient p = signIn(plain, PASSWORD);
        Response notOwner = post(p, "/api/v1/auth/firms", "{\"name\":\"Self Made Firm\"}", null);
        assertThat(notOwner.status()).isEqualTo(403);
        assertThat(notOwner.body()).isEqualTo("{\"error\":\"NOT_AN_OWNER\"}");
        assertThat(owner.queryForObject("select count(*) from firm where name in ('Blocked Firm', 'Self Made Firm')", Integer.class)).isZero();
    }

    // ---- 2. employees ---------------------------------------------------------------------------------------------------------------

    @Test
    void anOwnerInvitesAnEmployeeWhoGetsAccessToThatFirmOnly() {
        Owner o = newOwner("team-owner");
        long firm = createFirm(o, "Team Firm");
        long other = createFirm(newOwner("other-owner"), "Other Firm");
        select(o.session(), firm);
        String emp = "employee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";

        Response invite = post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"" + emp + "\",\"role\":\"OWNER\",\"firmId\":" + other + "}", firm);
        assertThat(invite.status()).as(invite.body()).isEqualTo(202);
        assertThat(invite.body()).as("the token is never in the response").doesNotContain("token").doesNotContain("#");
        assertThat(owner.queryForObject("select firm_id from firm_invitation where email = ?", Long.class, emp)).as("the session's firm, not the requested one").isEqualTo(firm);
        assertThat(owner.queryForObject("select role from firm_invitation where email = ?", String.class, emp)).as("always EMPLOYEE").isEqualTo("EMPLOYEE");

        String token = tokenMailedTo(emp);
        assertThat(owner.queryForObject("select count(*) from firm_invitation i where i::text like ?", Integer.class, "%" + token + "%")).as("only a digest is stored").isZero();
        assertThat(owner.queryForObject("select count(*) from audit_event e where e::text like ?", Integer.class, "%" + token + "%")).isZero();
        assertThat(((Number) JsonPath.read(get(o.session(), "/api/v1/firm/invitations", firm).body(), "$.length()")).intValue()).isEqualTo(1);

        // a resend replaces the first invitation: the first link stops working
        assertThat(post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"" + emp + "\"}", firm).status()).isEqualTo(202);
        String second = tokenMailedTo(emp);
        assertThat(second).isNotEqualTo(token);
        assertThat(accept(browser(), token, PASSWORD, "Employee").body()).isEqualTo("{\"error\":\"INVITATION_INVALID\"}");

        HttpTestClient invitee = browser();
        Response ok = accept(invitee, second, PASSWORD, "Employee One");
        assertThat(ok.status()).isEqualTo(200);
        assertThat((Object) ok.json("$.kind")).isEqualTo("EMPLOYEE");
        assertThat(owner.queryForList("select firm_id from firm_member where user_id = ?", Long.class, userId(emp))).containsExactly(firm);
        assertThat(owner.queryForObject("select role from firm_member where user_id = ?", String.class, userId(emp))).isEqualTo("EMPLOYEE");
        assertThat(accept(browser(), second, PASSWORD, "Again").status()).as("single use").isEqualTo(400);

        // the employee signs in, lands in their only firm and sees only its data
        HttpTestClient e = signIn(emp, PASSWORD);
        assertThat(get(e, "/api/v1/masters/items", firm).status()).isEqualTo(200);
        assertThat(post(e, "/api/v1/auth/active-firm", "{\"firmId\":" + other + "}", null).status()).isEqualTo(403);
        assertThat(get(e, "/api/v1/masters/items", other).body()).contains("FIRM_MISMATCH");
        // and has no authority over the team, the firm list of owners, or creating firms
        assertThat(post(e, "/api/v1/auth/firms", "{\"name\":\"Employee Firm\"}", null).body()).contains("NOT_AN_OWNER");
        assertThat(post(e, "/api/v1/firm/invitations", "{\"email\":\"someone@example.test\"}", firm).body()).contains("PERMISSION_DENIED");
        assertThat(get(e, "/api/v1/firm/members", firm).body()).contains("PERMISSION_DENIED");
        assertThat(post(e, "/api/v1/firm/members/1/deactivate", null, firm).body()).contains("PERMISSION_DENIED");
        assertThat(owner.queryForObject("select count(*) from firm where name = 'Employee Firm'", Integer.class)).isZero();

        // the owner deactivates and reactivates; the employee's live session follows at once
        List<Map<String, Object>> members = JsonPath.read(get(o.session(), "/api/v1/firm/members", firm).body(), "$");
        assertThat(members).extracting(m -> m.get("role")).containsExactly("OWNER", "EMPLOYEE");
        long employeeMembership = ((Number) members.get(1).get("id")).longValue();
        assertThat(post(o.session(), "/api/v1/firm/members/" + employeeMembership + "/deactivate", null, firm).status()).isEqualTo(204);
        assertThat(get(e, "/api/v1/masters/items", firm).body()).contains("NO_MEMBERSHIP");
        assertThat(post(o.session(), "/api/v1/firm/members/" + employeeMembership + "/activate", null, firm).status()).isEqualTo(204);
        assertThat(get(e, "/api/v1/masters/items", firm).status()).isEqualTo(200);
        // the owner's own membership cannot be changed, and another firm's members cannot be reached
        long ownerMembership = ((Number) members.get(0).get("id")).longValue();
        assertThat(post(o.session(), "/api/v1/firm/members/" + ownerMembership + "/deactivate", null, firm).status()).isEqualTo(422);
        long foreign = owner.queryForObject("select id from firm_member where firm_id = ?", Long.class, other);
        assertThat(post(o.session(), "/api/v1/firm/members/" + foreign + "/deactivate", null, firm).status()).isEqualTo(404);
        assertThat(owner.queryForObject("select status from firm_member where id = ?", String.class, foreign)).isEqualTo("ACTIVE");
    }

    @Test
    void anOwnerCanRevokeAPendingInvitationAndCannotInviteExistingMembers() {
        Owner o = newOwner("revoke-owner");
        long firm = createFirm(o, "Revoke Firm");
        select(o.session(), firm);
        String emp = "pending-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        assertThat(post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"" + emp + "\"}", firm).status()).isEqualTo(202);
        String token = tokenMailedTo(emp);
        long id = owner.queryForObject("select id from firm_invitation where email = ?", Long.class, emp);
        assertThat(post(o.session(), "/api/v1/firm/invitations/" + id + "/revoke", null, firm).status()).isEqualTo(204);
        assertThat(accept(browser(), token, PASSWORD, "x").body()).contains("INVITATION_INVALID");
        assertThat(post(o.session(), "/api/v1/firm/invitations/987654321/revoke", null, firm).status()).isEqualTo(404);
        assertThat(post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"" + o.email() + "\"}", firm).body()).as("the owner is already a member").contains("ALREADY_MEMBER");
        assertThat(post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"not-an-email\"}", firm).body()).contains("INVALID_EMAIL");
        // an invitation of the owner of ANOTHER firm cannot be revoked by this owner
        Owner other = newOwner("revoke-other");
        long otherFirm = createFirm(other, "Revoke Other Firm");
        select(other.session(), otherFirm);
        assertThat(post(other.session(), "/api/v1/firm/invitations", "{\"email\":\"x-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test\"}", otherFirm).status()).isEqualTo(202);
        long foreign = owner.queryForObject("select id from firm_invitation where firm_id = ?", Long.class, otherFirm);
        assertThat(post(o.session(), "/api/v1/firm/invitations/" + foreign + "/revoke", null, firm).status()).isEqualTo(404);
        assertThat(owner.queryForObject("select revoked_at is null from firm_invitation where id = ?", Boolean.class, foreign)).isTrue();
    }

    @Test
    void anExistingAccountAcceptsAnEmployeeInvitationOnlyWhileSignedInAsTheInvitedAddress() {
        Owner o = newOwner("existing-owner");
        long firm = createFirm(o, "Existing Firm");
        select(o.session(), firm);
        String emp = "existing-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        accounts.create(emp, "Already here", PASSWORD);
        String stranger = "stranger-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        accounts.create(stranger, "Stranger", PASSWORD);

        assertThat(post(o.session(), "/api/v1/firm/invitations", "{\"email\":\"" + emp + "\"}", firm).status()).isEqualTo(202);
        String token = tokenMailedTo(emp);
        Response anonymous = accept(browser(), token, "another-long-password", "x");
        assertThat(anonymous.status()).isEqualTo(409);
        assertThat(anonymous.body()).isEqualTo("{\"error\":\"ACCOUNT_EXISTS\"}");
        assertThat(owner.queryForObject("select count(*) from firm_member where user_id = ?", Integer.class, userId(emp))).isZero();

        HttpTestClient wrong = signIn(stranger, PASSWORD);
        Response refused = post(wrong, "/api/v1/auth/invitations/accept-signed-in", "{\"token\":\"" + token + "\"}", null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).isEqualTo("{\"error\":\"INVITATION_INVALID\"}");
        assertThat(owner.queryForObject("select count(*) from firm_member where user_id = ?", Integer.class, userId(stranger))).isZero();

        HttpTestClient right = signIn(emp, PASSWORD);
        assertThat(post(right, "/api/v1/auth/invitations/accept-signed-in", "{\"token\":\"" + token + "\"}", null).status()).isEqualTo(200);
        assertThat(owner.queryForList("select role from firm_member where user_id = ?", String.class, userId(emp))).containsExactly("EMPLOYEE");
        assertThat(post(right, "/api/v1/auth/invitations/accept-signed-in", "{\"token\":\"" + token + "\"}", null).status()).as("single use").isEqualTo(400);
    }

    // ---- 3. nothing else creates a person, a role or a firm ------------------------------------------------------------------------

    @Test
    void thereIsNoSelfRegistrationAndNoWayToChooseARoleOrAnotherFirm() {
        HttpTestClient anon = browser();
        String csrf = anon.csrf();
        int users = owner.queryForObject("select count(*) from app_user", Integer.class);
        int members = owner.queryForObject("select count(*) from firm_member", Integer.class);
        for (String path : List.of("/api/v1/auth/register", "/api/v1/auth/signup", "/api/v1/users", "/api/v1/auth/users", "/api/v1/register", "/api/v1/firm/members",
                "/api/v1/auth/firms", "/api/v1/auth/invitations/accept-signed-in", "/api/v1/auth/active-firm")) {
            Response r = anon.post(path, "{\"email\":\"mallory@example.test\",\"password\":\"" + PASSWORD + "\",\"role\":\"OWNER\",\"name\":\"Mallory\"}", CSRF_HEADER, csrf);
            assertThat(r.status()).as("anonymous POST " + path).isEqualTo(401);
        }
        // the public accept endpoint creates a person only for a valid token, whatever else the request says
        Response r = anon.post("/api/v1/auth/invitations/accept", "{\"token\":\"" + OneTimeTokens.issue().raw() + "\",\"password\":\"" + PASSWORD
                + "\",\"email\":\"mallory@example.test\",\"role\":\"OWNER\",\"firmId\":1}", CSRF_HEADER, csrf);
        assertThat(r.status()).isEqualTo(400);
        assertThat(owner.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(users);
        assertThat(owner.queryForObject("select count(*) from firm_member", Integer.class)).isEqualTo(members);
        assertThat(owner.queryForObject("select count(*) from app_user where email = 'mallory@example.test'", Integer.class)).isZero();
        // without a CSRF token even a valid-looking request is refused before anything is read
        assertThat(anon.post("/api/v1/auth/invitations/accept", "{}").status()).isEqualTo(403);
    }

    @Test
    void anOwnerOfOneFirmIsOnlyAnEmployeeInAnotherAndHasOwnerRightsOnlyWhereTheyAreOwner() {
        Owner a = newOwner("dual-a");
        Owner b = newOwner("dual-b");
        long firmA = createFirm(a, "Dual A Firm");
        long firmB = createFirm(b, "Dual B Firm");
        select(b.session(), firmB);
        // B invites A's email as an employee of B's firm: A has an account, so A accepts while signed in
        assertThat(post(b.session(), "/api/v1/firm/invitations", "{\"email\":\"" + a.email() + "\"}", firmB).status()).isEqualTo(202);
        assertThat(post(a.session(), "/api/v1/auth/invitations/accept-signed-in", "{\"token\":\"" + tokenMailedTo(a.email()) + "\"}", null).status()).isEqualTo(200);
        select(a.session(), firmB);
        assertThat(post(a.session(), "/api/v1/firm/invitations", "{\"email\":\"x@example.test\"}", firmB).body()).as("employee in B").contains("PERMISSION_DENIED");
        select(a.session(), firmA);
        assertThat(post(a.session(), "/api/v1/firm/invitations", "{\"email\":\"" + "y-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test\"}", firmA).status()).as("owner in A").isEqualTo(202);
    }
}
