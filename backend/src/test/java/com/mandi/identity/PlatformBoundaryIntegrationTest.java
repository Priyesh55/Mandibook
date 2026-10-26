package com.mandi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.OutputStream;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/**
 * Proof that the platform role cannot reach business data (docs/AUTHENTICATION_SECURITY_DECISIONS.md section 4.4, DB-1 to
 * DB-10). The checks are generated from the system catalog, not from a hand-written table list, so a table, view or function
 * added by a later migration is covered automatically and fails here until a decision is recorded. Every attempt below runs
 * over a REAL connection as {@code mandi_platform} against a throw-away database migrated with the real migrations, filled
 * with synthetic business data that uses valid firm and owner ids. Synthetic data only.
 */
class PlatformBoundaryIntegrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();
    private static final String HASH = "{bcrypt}$2a$12$" + "a".repeat(53);
    private static final String PLATFORM = TestcontainersConfiguration.PLATFORM_ROLE;
    private static final List<String> TABLE_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    /** The whole of the platform's access, as decided in the memo (4.2). Everything else must be denied. */
    private static final Map<String, Set<String>> ALLOWED = new TreeMap<>(Map.of(
            "platform.platform_admin", Set.of("SELECT", "UPDATE"),
            "platform.owner_account", Set.of("SELECT", "INSERT", "UPDATE"),
            "platform.owner_invitation", Set.of("SELECT", "INSERT", "UPDATE"),
            "platform.owner_user_summary", Set.of("SELECT")));

    private String url;
    private JdbcTemplate owner;
    private JdbcTemplate platform;
    private long firmId;
    private long ownerUserId;
    private long employeeUserId;

    @BeforeEach
    void databaseWithSyntheticBusinessData() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "plt_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + db + "$1");
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(MigrationVersion.LATEST).load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
        platform = new JdbcTemplate(TestcontainersConfiguration.platformDataSource(url));

        firmId = owner.queryForObject("insert into firm (code, name) values ('SYN1', 'Synthetic Secret Firm') returning id", Long.class);
        owner.update("insert into farmer (name, firm_id) values ('Synthetic Secret Farmer', ?)", firmId);
        ownerUserId = owner.queryForObject("insert into app_user (email, display_name, password_hash) values ('owner@example.test', 'Owner One', ?) returning id", Long.class, HASH);
        employeeUserId = owner.queryForObject("insert into app_user (email, display_name, password_hash) values ('employee@example.test', 'Employee One', ?) returning id", Long.class, HASH);
        owner.update("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER')", firmId, ownerUserId);
        owner.update("insert into firm_member (firm_id, user_id, role) values (?, ?, 'EMPLOYEE')", firmId, employeeUserId);
        owner.update("insert into platform.owner_account (display_name, status, trial_started_at, trial_ends_at, owner_user_id) "
                + "values ('Org One', 'TRIAL', now() - interval '1 day', now() + interval '13 days', ?)", ownerUserId);
        owner.update("insert into platform.platform_admin (email, password_hash) values ('admin@example.test', ?)", HASH);
    }

    private static String sqlState(Throwable e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        return cause instanceof PSQLException p ? p.getSQLState() : "not-a-database-error:" + cause;
    }

    private void assertPlatformDenied(String sql) {
        Throwable e = catchThrowable(() -> platform.execute(sql));
        assertThat(e).as("the database must refuse: " + sql).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(e)).as(sql).isEqualTo("42501");
    }

    /** Every relation the role could use, in every non-system schema, by the system catalog. */
    private List<String> relations() {
        return owner.queryForList("select n.nspname || '.' || c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where c.relkind in ('r', 'v', 'm', 'p', 'f') and n.nspname not in ('pg_catalog', 'information_schema') "
                + "and n.nspname not like 'pg_toast%' order by 1", String.class);
    }

    private Map<String, Set<String>> effectiveTablePrivileges(String role) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String rel : relations()) {
            Set<String> granted = new TreeSet<>();
            for (String p : TABLE_PRIVILEGES) {
                if (Boolean.TRUE.equals(owner.queryForObject("select has_table_privilege(?, ?::regclass, ?)", Boolean.class, role, rel, p))) granted.add(p);
            }
            if (!granted.isEmpty()) out.put(rel, granted);
        }
        return out;
    }

    // ---- DB-1, DB-3: catalog-generated, nothing outside the allow-list ----------------------------------------------

    @Test
    void theEffectivePlatformPrivilegesAreExactlyTheAllowList() {
        assertThat(effectiveTablePrivileges(PLATFORM)).as("DB-1/DB-3: effective privileges, including anything inherited from PUBLIC")
                .isEqualTo(ALLOWED);
        for (String rel : relations()) {
            if (ALLOWED.containsKey(rel)) continue;
            assertThat(owner.queryForObject("select has_any_column_privilege(?, ?::regclass, 'SELECT,INSERT,UPDATE,REFERENCES')", Boolean.class, PLATFORM, rel))
                    .as("column-level privilege on " + rel).isFalse();
        }
    }

    @Test
    void thePlatformRoleHasNoSequenceFunctionOrBusinessSchemaPrivilegeAnywhere() {
        for (String seq : owner.queryForList("select n.nspname || '.' || c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where c.relkind = 'S' and n.nspname not in ('pg_catalog', 'information_schema')", String.class)) {
            for (String p : List.of("USAGE", "SELECT", "UPDATE")) {
                assertThat(owner.queryForObject("select has_sequence_privilege(?, ?::regclass, ?)", Boolean.class, PLATFORM, seq, p)).as(p + " on " + seq).isFalse();
            }
        }
        List<String> executable = owner.queryForList("select n.nspname || '.' || p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname not in ('pg_catalog', 'information_schema') and has_function_privilege(?, p.oid, 'EXECUTE')", String.class, PLATFORM);
        assertThat(executable).as("DB-6: functions the platform role may execute (the allow-list is empty)").isEmpty();
        assertThat(owner.queryForObject("select has_schema_privilege(?, 'public', 'USAGE')", Boolean.class, PLATFORM)).isFalse();
        assertThat(owner.queryForObject("select has_schema_privilege(?, 'public', 'CREATE')", Boolean.class, PLATFORM)).isFalse();
        assertThat(owner.queryForObject("select has_schema_privilege(?, 'platform', 'CREATE')", Boolean.class, PLATFORM)).isFalse();
        assertThat(owner.queryForObject("select has_schema_privilege(?, 'platform', 'USAGE')", Boolean.class, PLATFORM)).isTrue();
        assertThat(owner.queryForObject("select has_database_privilege(?, current_database(), 'CREATE')", Boolean.class, PLATFORM)).isFalse();
    }

    @Test
    void postgresqlsDefaultAccessForEveryRoleToTheBusinessSchemaIsWithdrawn() {
        // the hazard found in the memo: PUBLIC could use schema public and execute its functions
        assertThat(owner.queryForObject("select count(*) from pg_namespace n, aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) a "
                + "where n.nspname = 'public' and a.grantee = 0", Integer.class)).as("PUBLIC privileges on schema public").isZero();
        assertThat(owner.queryForObject("select count(*) from pg_proc p, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a "
                + "where p.pronamespace = 'public'::regnamespace and a.grantee = 0 and a.privilege_type = 'EXECUTE'", Integer.class))
                .as("functions in public that PUBLIC may execute").isZero();
    }

    // ---- DB-2: real statements as the platform role, using valid firm and owner ids -----------------------------------

    @Test
    void everyStatementAgainstEveryBusinessTableIsRefusedByTheDatabase() throws Exception {
        List<String> business = owner.queryForList("select tablename from pg_tables where schemaname = 'public'", String.class);
        assertThat(business).contains("firm", "farmer", "firm_member", "app_user", "credential_token", "audit_event", "aadat_bill");
        for (String t : business) {
            assertPlatformDenied("select count(*) from public." + t);
            assertPlatformDenied("select * from public." + t + " limit 1");
            assertPlatformDenied("delete from public." + t);
            assertPlatformDenied("truncate public." + t);
            assertPlatformDenied("insert into public." + t + " default values");
            assertPlatformDenied("update public." + t + " set id = id");
        }
        // with valid ids in the WHERE clause, through the usual doors
        assertPlatformDenied("select * from public.firm where id = " + firmId);
        assertPlatformDenied("select * from public.farmer where firm_id = " + firmId);
        assertPlatformDenied("select * from public.firm_member where user_id = " + ownerUserId);
        assertPlatformDenied("select email from public.app_user where id = " + ownerUserId);
        assertPlatformDenied("select * from public.account_access where owner_user_id = " + ownerUserId);
        assertPlatformDenied("update public.firm set name = 'x' where id = " + firmId);
        // COPY ... TO STDOUT through the driver's copy API
        try (Connection c = platform.getDataSource().getConnection()) {
            for (String t : List.of("firm", "farmer", "app_user", "firm_member")) {
                Throwable e = catchThrowable(() -> c.unwrap(PGConnection.class).getCopyAPI().copyOut("copy public." + t + " to stdout", OutputStream.nullOutputStream()));
                assertThat(e).as("copy " + t).isInstanceOf(PSQLException.class);
                assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501");
            }
        }
    }

    @Test
    void thePlatformRoleCannotEscalateOrCreateAnything() {
        assertPlatformDenied("create table platform.sneaky (i int)");
        assertPlatformDenied("create table public.sneaky (i int)");
        assertPlatformDenied("create schema sneaky");
        assertPlatformDenied("create function platform.sneaky() returns int language sql as 'select 1'");
        assertPlatformDenied("set role mandi_app");
        assertPlatformDenied("set role " + PG.getUsername());
        assertPlatformDenied("select pg_read_file('/etc/passwd')");
        assertPlatformDenied("alter role mandi_platform superuser");
        assertPlatformDenied("grant select on public.firm to mandi_platform");
        assertPlatformDenied("set session_replication_role = replica");
        assertPlatformDenied("drop view platform.owner_user_summary");
    }

    @Test
    void whatThePlatformMayDoWorksAndNothingMore() {
        platform.update("insert into platform.owner_account (display_name) values ('Org Two')");
        platform.update("update platform.owner_account set display_name = 'Org Two renamed' where display_name = 'Org Two'");
        platform.update("update platform.platform_admin set last_login_at = now()");
        assertThat(platform.queryForObject("select count(*) from platform.owner_account", Integer.class)).isEqualTo(2);
        assertPlatformDenied("delete from platform.owner_account");
        assertPlatformDenied("truncate platform.owner_account");
        assertPlatformDenied("insert into platform.platform_admin (email, password_hash) values ('x@example.test', '" + HASH + "')");
        assertPlatformDenied("delete from platform.platform_admin");
    }

    // ---- DB-4, DB-5: no inherited roles, no special attributes ---------------------------------------------------------

    @Test
    void thePlatformRoleIsAnOrdinaryLoginRoleWithNoMembershipsAndNoAttributes() {
        Map<String, Object> r = owner.queryForMap("select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolreplication, rolinherit, rolcanlogin, rolconnlimit "
                + "from pg_roles where rolname = ?", PLATFORM);
        assertThat(r.get("rolsuper")).isEqualTo(false);
        assertThat(r.get("rolcreatedb")).isEqualTo(false);
        assertThat(r.get("rolcreaterole")).isEqualTo(false);
        assertThat(r.get("rolbypassrls")).isEqualTo(false);
        assertThat(r.get("rolreplication")).isEqualTo(false);
        assertThat(r.get("rolinherit")).isEqualTo(false);
        assertThat(r.get("rolcanlogin")).isEqualTo(true);
        assertThat((Integer) r.get("rolconnlimit")).isPositive();
        assertThat(membershipsOfPlatformRole()).as("no role is granted to the platform role (not mandi_app, not pg_read_all_data, ...)").isEmpty();
        assertThat(owner.queryForObject("select count(*) from pg_tables where tableowner = ?", Integer.class, PLATFORM)).as("owns nothing").isZero();
        assertThat(owner.queryForObject("select count(*) from pg_namespace where nspowner = (select oid from pg_roles where rolname = ?)", Integer.class, PLATFORM)).isZero();
    }

    private List<String> membershipsOfPlatformRole() {
        return owner.queryForList("select r.rolname from pg_auth_members m join pg_roles r on r.oid = m.roleid "
                + "where m.member = (select oid from pg_roles where rolname = ?)", String.class, PLATFORM);
    }

    // ---- DB-7: the owner-only summary view ----------------------------------------------------------------------------

    @Test
    void theOwnerSummaryShowsOwnersOnlyAndOnlyTheDecidedColumns() {
        assertThat(owner.queryForList("select column_name from information_schema.columns where table_schema = 'platform' and table_name = 'owner_user_summary' "
                + "order by ordinal_position", String.class))
                .containsExactly("owner_account_id", "user_id", "email", "display_name", "user_status", "last_login_at", "email_verified_at");
        List<Map<String, Object>> rows = platform.queryForList("select * from platform.owner_user_summary");
        assertThat(rows).as("the owner appears, the employee does not").hasSize(1);
        assertThat(rows.get(0).get("email")).isEqualTo("owner@example.test");
        assertThat(platform.queryForList("select email from platform.owner_user_summary where email = 'employee@example.test'", String.class)).isEmpty();
        for (String column : List.of("password_hash", "failed_login_count", "locked_until", "firm_id", "role", "token_hash")) {
            Throwable e = catchThrowable(() -> platform.queryForList("select " + column + " from platform.owner_user_summary"));
            assertThat(sqlState(e)).as(column).isEqualTo("42703");              // undefined_column
        }
        // an app_user that is not an owner account's user never appears, even when it has many memberships
        long extra = owner.queryForObject("insert into app_user (email, password_hash) values ('extra@example.test', ?) returning id", Long.class, HASH);
        owner.update("insert into firm_member (firm_id, user_id, role) values (?, ?, 'EMPLOYEE')", firmId, extra);
        assertThat(platform.queryForObject("select count(*) from platform.owner_user_summary", Integer.class)).isEqualTo(1);
    }

    // ---- DB-8: objects added later do not open a path ---------------------------------------------------------------------

    @Test
    void anObjectAddedLaterGivesThePlatformRoleNothing() {
        owner.execute("create table public.later_business_table (id int, secret text)");
        owner.execute("insert into public.later_business_table values (1, 'secret')");
        owner.execute("create view public.later_business_view as select * from public.firm");
        owner.execute("create function public.later_helper() returns text language sql as $$ select 'executed' $$");
        owner.execute("create function public.later_leak() returns setof text language sql security definer set search_path = pg_catalog, public "
                + "as $$ select name from public.firm $$");
        owner.execute("create table platform.later_platform_table (id int)");
        owner.execute("create function platform.later_platform_function() returns int language sql as 'select 1'");
        assertPlatformDenied("select * from public.later_business_table");
        assertPlatformDenied("select * from public.later_business_view");
        assertPlatformDenied("select public.later_helper()");
        assertPlatformDenied("select * from public.later_leak()");
        assertPlatformDenied("select * from platform.later_platform_table");
        assertPlatformDenied("select platform.later_platform_function()");
        assertThat(effectiveTablePrivileges(PLATFORM)).as("still exactly the allow-list").isEqualTo(ALLOWED);
    }

    @Test
    void everySecurityDefinerFunctionPinsItsSearchPath() {
        owner.execute("create function public.definer_ok() returns int language sql security definer set search_path = pg_catalog, public as 'select 1'");
        List<String> unpinned = owner.queryForList("select p.proname from pg_proc p where p.prosecdef and p.pronamespace in ('public'::regnamespace, 'platform'::regnamespace) "
                + "and (p.proconfig is null or not exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%'))", String.class);
        assertThat(unpinned).as("DB-6: SECURITY DEFINER functions without a pinned search_path").isEmpty();
    }

    // ---- misconfigurations are detected by the same checks ---------------------------------------------------------------

    @Test
    void grantingBusinessRoleMembershipToThePlatformRoleIsDetected() {
        assertThat(membershipsOfPlatformRole()).isEmpty();
        owner.execute("grant mandi_app to " + PLATFORM);
        try {
            assertThat(membershipsOfPlatformRole()).as("the membership check catches it").containsExactly("mandi_app");
            // and it matters: the role can now become mandi_app and read the business views
            Integer rows = platform.execute((Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set role mandi_app");
                    var rs = st.executeQuery("select count(*) from public.account_access");
                    rs.next();
                    return rs.getInt(1);
                }
            });
            assertThat(rows).isEqualTo(1);
        } finally {
            owner.execute("revoke mandi_app from " + PLATFORM);
        }
        assertThat(membershipsOfPlatformRole()).isEmpty();
    }

    @Test
    void aViewOverBusinessDataGrantedByMistakeBreaksTheAllowListCheck() {
        owner.execute("create view platform.leaky_view as select * from public.firm");
        owner.execute("grant select on platform.leaky_view to " + PLATFORM);
        assertThat(platform.queryForObject("select count(*) from platform.leaky_view", Integer.class)).as("views read with their owner's rights")
                .isEqualTo(owner.queryForObject("select count(*) from firm", Integer.class)).isPositive();
        Map<String, Set<String>> effective = effectiveTablePrivileges(PLATFORM);
        List<String> extra = new ArrayList<>(effective.keySet());
        extra.removeAll(ALLOWED.keySet());
        assertThat(extra).as("the allow-list comparison names the stray grant").containsExactly("platform.leaky_view");
    }

    // ---- DB-10 and the contract surface in the other direction -------------------------------------------------------------

    private JdbcTemplate runtime() {
        return new JdbcTemplate(new DriverManagerDataSource(url, TestcontainersConfiguration.RUNTIME_ROLE, TestcontainersConfiguration.RUNTIME_PASSWORD));
    }

    @Test
    void theBusinessRoleSeesAccountStateOnlyThroughTheViewAndNeverThePlatformTables() {
        JdbcTemplate app = runtime();
        assertThat(owner.queryForList("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'account_access' "
                + "order by ordinal_position", String.class))
                .containsExactly("owner_user_id", "owner_account_id", "stored_status", "trial_started_at", "trial_ends_at", "paid_until",
                        "access_grace_until", "plan_code", "effective_status");
        assertThat(app.queryForObject("select effective_status from public.account_access where owner_user_id = ?", String.class, ownerUserId)).isEqualTo("TRIAL");
        for (String sql : List.of("select * from platform.owner_account", "select * from platform.platform_admin", "select * from platform.owner_user_summary",
                "select * from platform.owner_invitation", "insert into platform.owner_account (display_name) values ('x')")) {
            Throwable e = catchThrowable(() -> app.execute(sql));
            assertThat(sqlState(e)).as(sql).isEqualTo("42501");
        }
        Throwable write = catchThrowable(() -> app.update("update public.account_access set stored_status = 'ACTIVE'"));
        assertThat(sqlState(write)).as("the view is read-only for the business role").isEqualTo("42501");
    }

    @Test
    void effectiveStatusIsDerivedFromTheStoredDatesAndTheDatabaseClock() {
        JdbcTemplate app = runtime();
        String ins = "insert into platform.owner_account (display_name, status, trial_started_at, trial_ends_at, paid_until, access_grace_until, suspended_reason_code, owner_user_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)";
        Timestamp past = Timestamp.valueOf("2020-01-01 00:00:00"), pastLater = Timestamp.valueOf("2020-01-15 00:00:00");
        Timestamp future = Timestamp.valueOf("2999-01-01 00:00:00");
        long u1 = newUser("u1"), u2 = newUser("u2"), u3 = newUser("u3"), u4 = newUser("u4"), u5 = newUser("u5"), u6 = newUser("u6");
        owner.update(ins, "expired trial", "TRIAL", past, pastLater, null, null, null, u1);
        owner.update(ins, "expired trial with grace", "TRIAL", past, pastLater, null, future, null, u2);
        owner.update(ins, "paid lapsed", "ACTIVE", null, null, pastLater, null, null, u3);
        owner.update(ins, "paid lapsed with grace", "ACTIVE", null, null, pastLater, future, null, u4);
        owner.update(ins, "paid open ended", "ACTIVE", null, null, null, null, null, u5);
        owner.update(ins, "suspended", "SUSPENDED", null, null, null, null, "NON_PAYMENT", u6);
        owner.update("insert into platform.owner_account (display_name) values ('invited, not yet accepted')");     // no owner user yet
        Map<Long, String> byUser = new TreeMap<>();
        app.query("select owner_user_id, effective_status from public.account_access", rs -> {
            byUser.put(rs.getLong(1), rs.getString(2));
        });
        assertThat(byUser).containsEntry(u1, "EXPIRED_TRIAL").containsEntry(u2, "TRIAL").containsEntry(u3, "EXPIRED")
                .containsEntry(u4, "ACTIVE").containsEntry(u5, "ACTIVE").containsEntry(u6, "SUSPENDED").containsEntry(ownerUserId, "TRIAL");
        assertThat(byUser).as("an account whose invitation is not accepted has no row").hasSize(7);
    }

    private long newUser(String tag) {
        return owner.queryForObject("insert into app_user (email, password_hash) values (?, ?) returning id", Long.class, tag + "@example.test", HASH);
    }
}
