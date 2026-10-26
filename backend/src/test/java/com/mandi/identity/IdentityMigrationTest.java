package com.mandi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/**
 * V6 (identity core) and its rollback U6, in throw-away databases on the project's PostgreSQL major version (16).
 * Every statement runs as the owner/migrator, so what is tested is the schema's own constraints, not application
 * code. Synthetic data only. The runtime role's privileges are checked with the real role at the end.
 */
class IdentityMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();
    /** Shaped like a delegating-encoder hash; the value is meaningless. */
    private static final String HASH = "{bcrypt}$2a$12$" + "a".repeat(53);
    private static final byte[] DIGEST_A = digest('a');
    private static final byte[] DIGEST_B = digest('b');

    private String url;
    private JdbcTemplate jdbc;

    private static byte[] digest(char c) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) c);
        return b;
    }

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "idn_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + db + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(MigrationVersion.LATEST).load().migrate();
    }

    private void runRollback(String script) throws IOException {
        try (var in = getClass().getResourceAsStream("/db/rollback/" + script)) {
            assertThat(in).as(script + " is on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** SQLSTATE of the failure, so that the database's own refusal is what is asserted. */
    private String refused(String sql, Object... args) {
        Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(sql, args));
        assertThat(e).as("the database must refuse: " + sql).isInstanceOf(DataAccessException.class);
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        assertThat(cause).isInstanceOf(PSQLException.class);
        return ((PSQLException) cause).getSQLState();
    }

    private static final String UNIQUE = "23505", CHECK = "23514", FK = "23503";

    private long firm(String code) {
        return jdbc.queryForObject("insert into firm (code, name) values (?, ?) returning id", Long.class, code, "Firm " + code);
    }

    private long user(String email) {
        return jdbc.queryForObject("insert into app_user (email, password_hash) values (?, ?) returning id", Long.class, email, HASH);
    }

    private long member(long firm, long user, String role) {
        return jdbc.queryForObject("insert into firm_member (firm_id, user_id, role) values (?, ?, ?) returning id", Long.class, firm, user, role);
    }

    // ---- fresh database and migration from the current schema ----------------------------------------------

    @Test
    void migratesFreshAndCreatesTheExpectedTablesInTheirSchemas() {
        migrateToLatest();
        List<String> tables = jdbc.queryForList("select schemaname || '.' || tablename from pg_tables "
                + "where schemaname in ('public', 'platform') and tablename <> 'flyway_schema_history' order by 1", String.class);
        assertThat(tables).contains("public.app_user", "public.firm_member", "public.credential_token", "public.firm_invitation",
                "platform.platform_admin", "platform.owner_account", "platform.owner_invitation");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '6' and success", Integer.class)).isEqualTo(1);
    }

    @Test
    void migratingFromTheCurrentSchemaKeepsEveryExistingRowAndTableDefinition() {
        migrateTo("5");
        long firm = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        jdbc.update("insert into farmer (name, firm_id) values ('Synthetic Farmer', ?)", firm);
        jdbc.update("insert into buyer (name, firm_id) values ('Synthetic Buyer', ?)", firm);
        String before = fingerprint();
        String definitionsBefore = existingTableDefinitions();

        migrateTo("9");      // V10 deliberately adds audit/bill columns: AuditActorMigrationTest proves it keeps old rows

        assertThat(fingerprint()).as("firm, master and audit rows untouched").isEqualTo(before);
        assertThat(existingTableDefinitions()).as("no existing table, column or constraint changed").isEqualTo(definitionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
    }

    private String fingerprint() {
        return jdbc.queryForObject("select (select count(*) from firm) || '/' || (select count(*) from farmer) || '/' || (select count(*) from buyer) "
                + "|| '/' || (select count(*) from item) || '/' || (select count(*) from audit_event) || '/' "
                + "|| coalesce((select string_agg(code || ':' || name, ',' order by code) from firm), '')", String.class);
    }

    private String existingTableDefinitions() {
        String tables = "('firm','farmer','buyer','village','item','expense_head','aadat_bill','aadat_lot','aadat_expense','audit_event')";
        return jdbc.queryForObject("select string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable, ',' "
                + "order by table_name, column_name) from information_schema.columns where table_schema = 'public' and table_name in " + tables, String.class)
                + "\n" + jdbc.queryForObject("select string_agg(conrelid::regclass::text || '.' || conname || ':' || pg_get_constraintdef(oid), ',' "
                + "order by conrelid::regclass::text, conname) from pg_constraint where connamespace = 'public'::regnamespace "
                + "and conrelid::regclass::text in " + tables, String.class);
    }

    // ---- app_user ------------------------------------------------------------------------------------------

    @Test
    void emailsAreUniqueAndMustBeTrimmedAndLowerCase() {
        migrateToLatest();
        user("owner@example.test");
        assertThat(refused("insert into app_user (email, password_hash) values ('owner@example.test', ?)", HASH)).isEqualTo(UNIQUE);
        assertThat(refused("insert into app_user (email, password_hash) values ('Owner2@example.test', ?)", HASH)).as("upper case").isEqualTo(CHECK);
        assertThat(refused("insert into app_user (email, password_hash) values (' owner3@example.test', ?)", HASH)).as("untrimmed").isEqualTo(CHECK);
        assertThat(refused("insert into app_user (email, password_hash) values ('', ?)", HASH)).isEqualTo(CHECK);
        assertThat(refused("insert into app_user (email, password_hash) values ('no-at-sign', ?)", HASH)).isEqualTo(CHECK);
        // not-null
        assertThat(refused("insert into app_user (email, password_hash) values (null, ?)", HASH)).isEqualTo("23502");
    }

    @Test
    void aPlaintextPasswordCanNotBeStored() {
        migrateToLatest();
        for (String bad : new String[] {"password123", "correct horse battery staple", "{bcrypt}short", "$2a$12$" + "a".repeat(53),
                "{bcrypt} " + "a".repeat(60), ""}) {
            assertThat(refused("insert into app_user (email, password_hash) values (?, ?)", "u" + UUID.randomUUID() + "@example.test", bad))
                    .as("rejected: " + bad).isEqualTo(CHECK);
        }
        assertThat(refused("insert into platform.platform_admin (email, password_hash) values ('pa@example.test', 'plaintext-password-here')"))
                .isEqualTo(CHECK);
    }

    @Test
    void userStatusAndFailureCounterAreConstrained() {
        migrateToLatest();
        long u = user("status@example.test");
        assertThat(jdbc.queryForObject("select status from app_user where id = ?", String.class, u)).isEqualTo("ACTIVE");
        jdbc.update("update app_user set status = 'DISABLED' where id = ?", u);
        assertThat(refused("update app_user set status = 'DELETED' where id = ?", u)).isEqualTo(CHECK);
        assertThat(refused("update app_user set status = 'PLATFORM_ADMIN' where id = ?", u)).isEqualTo(CHECK);
        assertThat(refused("update app_user set failed_login_count = -1 where id = ?", u)).isEqualTo(CHECK);
    }

    @Test
    void usersAndMembershipsAreNeverDeleted() {
        migrateToLatest();
        long f = firm("F-DEL");
        long u = user("keep@example.test");
        long m = member(f, u, "OWNER");
        assertThat(refused("delete from firm_member where id = ?", m)).isEqualTo(CHECK);
        assertThat(refused("delete from app_user where id = ?", u)).isEqualTo(CHECK);
        assertThat(refused("delete from firm where id = ?", f)).as("a firm with members cannot be deleted either").isEqualTo(FK);
    }

    // ---- firm_member -----------------------------------------------------------------------------------------

    @Test
    void aPersonHasAtMostOneMembershipPerFirm() {
        migrateToLatest();
        long f = firm("F-UNI");
        long u = user("one@example.test");
        member(f, u, "EMPLOYEE");
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, ?, 'EMPLOYEE')", f, u)).isEqualTo(UNIQUE);
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER')", f, u)).as("not even as owner").isEqualTo(UNIQUE);
    }

    @Test
    void oneOwnerManyFirmsAndEmployeesOnlyInAssignedFirms() {
        migrateToLatest();
        long owner = user("owner@example.test");
        long emp = user("employee@example.test");
        long f1 = firm("F-1"), f2 = firm("F-2"), f3 = firm("F-3");
        member(f1, owner, "OWNER");
        member(f2, owner, "OWNER");
        member(f3, owner, "OWNER");                    // no limit on the number of firms
        member(f1, emp, "EMPLOYEE");                   // the employee is assigned to ONE firm only
        assertThat(jdbc.queryForList("select firm_id from firm_member where user_id = ? and status = 'ACTIVE' order by firm_id", Long.class, owner))
                .containsExactly(f1, f2, f3);
        assertThat(jdbc.queryForList("select firm_id from firm_member where user_id = ?", Long.class, emp)).containsExactly(f1);
        // an owner of one firm may also be an employee elsewhere: owner-ness belongs to the membership
        long otherOwner = user("other@example.test");
        long f4 = firm("F-4");
        member(f4, otherOwner, "OWNER");
        member(f4, owner, "EMPLOYEE");
        assertThat(jdbc.queryForObject("select role from firm_member where firm_id = ? and user_id = ?", String.class, f4, owner)).isEqualTo("EMPLOYEE");
    }

    @Test
    void aFirmHasExactlyOneOwnerMembership() {
        migrateToLatest();
        long f = firm("F-OWN"), g = firm("F-OWN2");
        long a = user("a@example.test"), b = user("b@example.test");
        member(f, a, "OWNER");
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER')", f, b)).isEqualTo(UNIQUE);
        member(f, b, "EMPLOYEE");                                  // fine
        member(g, b, "OWNER");                                     // another firm may have another owner
    }

    @Test
    void roleAndStatusValuesAreConstrainedAndPlatformAdministratorIsNotARole() {
        migrateToLatest();
        long f = firm("F-ROLE");
        long u = user("role@example.test");
        for (String role : new String[] {"PLATFORM_ADMIN", "ADMIN", "owner", "", "SUPERUSER"}) {
            assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, ?, ?)", f, u, role)).as(role).isEqualTo(CHECK);
        }
        long m = member(f, u, "EMPLOYEE");
        assertThat(refused("update firm_member set status = 'DELETED' where id = ?", m)).isEqualTo(CHECK);
        assertThat(refused("update firm_member set role = 'PLATFORM_ADMIN' where id = ?", m)).isEqualTo(CHECK);
    }

    @Test
    void deactivationNeedsATimestampReactivationIsAllowedAndKeysNeverMove() {
        migrateToLatest();
        long f = firm("F-ST"), g = firm("F-ST2");
        long owner = user("o@example.test"), emp = user("e@example.test"), other = user("x@example.test");
        member(f, owner, "OWNER");
        long m = member(f, emp, "EMPLOYEE");
        assertThat(refused("update firm_member set status = 'INACTIVE' where id = ?", m)).as("INACTIVE needs deactivated_at").isEqualTo(CHECK);
        jdbc.update("update firm_member set status = 'INACTIVE', deactivated_at = now(), deactivated_by_user_id = ? where id = ?", owner, m);
        jdbc.update("update firm_member set status = 'ACTIVE', deactivated_at = null, deactivated_by_user_id = null where id = ?", m);   // reactivation
        assertThat(refused("update firm_member set firm_id = ? where id = ?", g, m)).as("a membership cannot move to another firm").isEqualTo(CHECK);
        assertThat(refused("update firm_member set user_id = ? where id = ?", other, m)).as("or to another person").isEqualTo(CHECK);
    }

    @Test
    void aMembershipNeedsARealFirmAndARealUser() {
        migrateToLatest();
        long f = firm("F-FK");
        long u = user("fk@example.test");
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, 999999, 'EMPLOYEE')", f)).isEqualTo(FK);
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (999999, ?, 'EMPLOYEE')", u)).isEqualTo(FK);
    }

    // ---- tokens ----------------------------------------------------------------------------------------------

    @Test
    void credentialTokensStoreOnlyA32ByteDigestAreUniqueAndConstrained() {
        migrateToLatest();
        long u = user("tok@example.test");
        String sql = "insert into credential_token (user_id, purpose, token_hash, expires_at) values (?, ?, ?, now() + interval '30 minutes')";
        jdbc.update(sql, u, "PASSWORD_RESET", DIGEST_A);
        assertThat(refused(sql, u, "PASSWORD_RESET", DIGEST_A)).as("same digest twice").isEqualTo(UNIQUE);
        assertThat(refused(sql, u, "PASSWORD_RESET", new byte[20])).as("not a SHA-256 digest").isEqualTo(CHECK);
        assertThat(refused(sql, u, "PASSWORD_RESET", new byte[43])).as("a raw 43-character token is refused").isEqualTo(CHECK);
        assertThat(refused(sql, u, "LOGIN", DIGEST_B)).as("unknown purpose").isEqualTo(CHECK);
        assertThat(refused("insert into credential_token (user_id, purpose, token_hash, expires_at) values (?, 'EMAIL_VERIFY', ?, now() - interval '1 minute')", u, DIGEST_B))
                .as("already expired").isEqualTo(CHECK);
        assertThat(refused(sql, 999999L, "EMAIL_VERIFY", DIGEST_B)).as("unknown user").isEqualTo(FK);
        long id = jdbc.queryForObject("select id from credential_token limit 1", Long.class);
        assertThat(refused("update credential_token set consumed_at = now(), superseded_at = now() where id = ?", id)).as("consumed and superseded").isEqualTo(CHECK);
    }

    @Test
    void thereIsNoColumnThatCouldHoldARawToken() {
        migrateToLatest();
        // every token column is bytea of exactly 32 bytes; no text column in these tables is meant for a token
        for (String t : List.of("public.credential_token", "public.firm_invitation", "platform.owner_invitation")) {
            assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema = split_part(?, '.', 1) "
                    + "and table_name = split_part(?, '.', 2) and column_name = 'token_hash'", String.class, t, t)).as(t).isEqualTo("bytea");
        }
        assertThat(jdbc.queryForList("select table_schema || '.' || table_name || '.' || column_name from information_schema.columns "
                + "where table_schema in ('public', 'platform') and column_name ~ '(^|_)(token|secret|raw|plain)' and column_name <> 'token_hash'", String.class))
                .as("no column named like a raw token or secret").isEmpty();
    }

    @Test
    void firmInvitationsAreEmployeeOnlySameFirmOnlyAndOneOpenPerEmail() {
        migrateToLatest();
        long f = firm("F-INV"), g = firm("F-INV2");
        long owner = user("io@example.test"), other = user("other@example.test"), invitee = user("invitee@example.test");
        long m = member(f, owner, "OWNER");
        long mOther = member(g, other, "OWNER");
        String sql = "insert into firm_invitation (firm_id, email, role, token_hash, invited_by_membership_id, expires_at) "
                + "values (?, ?, ?, ?, ?, now() + interval '7 days')";
        jdbc.update(sql, f, "new@example.test", "EMPLOYEE", DIGEST_A, m);
        assertThat(refused(sql, f, "x@example.test", "OWNER", DIGEST_B, m)).as("only employees are invited").isEqualTo(CHECK);
        assertThat(refused(sql, f, "x@example.test", "EMPLOYEE", DIGEST_B, mOther)).as("inviter from another firm").isEqualTo(FK);
        assertThat(refused(sql, f, "x@example.test", "EMPLOYEE", DIGEST_A, m)).as("same digest").isEqualTo(UNIQUE);
        assertThat(refused(sql, f, "new@example.test", "EMPLOYEE", DIGEST_B, m)).as("second open invitation, same email").isEqualTo(UNIQUE);
        assertThat(refused(sql, f, "New@Example.test", "EMPLOYEE", DIGEST_B, m)).as("not normalized").isEqualTo(CHECK);
        long id = jdbc.queryForObject("select id from firm_invitation limit 1", Long.class);
        jdbc.update("update firm_invitation set revoked_at = now() where id = ?", id);
        jdbc.update(sql, f, "new@example.test", "EMPLOYEE", DIGEST_B, m);          // allowed again after the first was revoked
        assertThat(refused("update firm_invitation set accepted_at = now() where id = ?", id)).as("accepted needs an acceptor").isEqualTo(CHECK);
        assertThat(refused("update firm_invitation set accepted_at = now(), accepted_by_user_id = ? where id = ?", invitee, id))
                .as("revoked and accepted").isEqualTo(CHECK);
    }

    // ---- platform world --------------------------------------------------------------------------------------

    @Test
    void platformAdministratorIsASeparateIdentityThatCanNeverBeAMember() {
        migrateToLatest();
        long admin = jdbc.queryForObject("insert into platform.platform_admin (email, password_hash) values ('pa@example.test', ?) returning id", Long.class, HASH);
        // the same email is NOT the same identity and does not collide with a business user
        user("pa@example.test");
        long f = firm("F-PA");
        // no firm_member row can reference a platform administrator: user_id references app_user only
        assertThat(refused("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER')", f, admin + 100000)).isEqualTo(FK);
        assertThat(jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'public.firm_member'::regclass and contype = 'f'", String.class))
                .allMatch(def -> !def.contains("platform"));
        // no foreign key joins the two schemas, in either direction
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint c join pg_class a on a.oid = c.conrelid join pg_class b on b.oid = c.confrelid "
                + "where c.contype = 'f' and a.relnamespace <> b.relnamespace", Integer.class)).as("cross-schema foreign keys").isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint c where c.contype = 'f' and (c.conrelid = 'platform.platform_admin'::regclass "
                + "or c.confrelid = 'platform.platform_admin'::regclass) and c.conrelid <> 'platform.owner_account'::regclass "
                + "and c.conrelid <> 'platform.owner_invitation'::regclass", Integer.class))
                .as("platform_admin is referenced only by platform audit-style columns").isZero();
        assertThat(refused("insert into platform.platform_admin (email, password_hash) values ('pa@example.test', ?)", HASH)).as("unique among admins").isEqualTo(UNIQUE);
    }

    @Test
    void ownerAccountsEnforceTheLifecycleShapeOfTheBlueprint() {
        migrateToLatest();
        String ins = "insert into platform.owner_account (display_name, status, trial_started_at, trial_ends_at, owner_user_id, suspended_reason_code) values (?, ?, ?, ?, ?, ?)";
        java.sql.Timestamp t0 = java.sql.Timestamp.valueOf("2026-10-20 10:00:00"), t14 = java.sql.Timestamp.valueOf("2026-11-03 10:00:00");
        jdbc.update(ins, "Org pending", "TRIAL", null, null, null, null);                      // invited, trial not started (RD-8)
        jdbc.update(ins, "Org trial", "TRIAL", t0, t14, 501L, null);
        assertThat(refused(ins, "x", "TRIAL", t0, null, null, null)).as("dates come together").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "TRIAL", t14, t0, null, null)).as("end after start").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "TRIAL", null, null, 502L, null)).as("accepted trial needs a period").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "SUSPENDED", null, null, null, null)).as("suspension needs a reason").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "ACTIVE", null, null, null, "NON_PAYMENT")).as("a reason only with SUSPENDED").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "SUSPENDED", null, null, null, "FREE_TEXT")).as("reason from the fixed list").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "EXPIRED_TRIAL", null, null, null, null)).as("expired trial is derived, never stored").isEqualTo(CHECK);
        assertThat(refused(ins, "x", "TEST", null, null, null, null)).as("TEST is not a commercial status").isEqualTo(CHECK);
        assertThat(refused(ins, "dup", "TRIAL", t0, t14, 501L, null)).as("one account per owner user").isEqualTo(UNIQUE);
        jdbc.update(ins, "Org suspended", "SUSPENDED", null, null, null, "NON_PAYMENT");
        // no firm-count column or limit exists on the account (AP-3)
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = 'platform' and table_name = 'owner_account'", String.class))
                .noneMatch(c -> c.contains("firm"));
    }

    @Test
    void ownerInvitationsStoreOnlyADigestAndAllowOneOpenPerAccount() {
        migrateToLatest();
        long acc = jdbc.queryForObject("insert into platform.owner_account (display_name) values ('Org') returning id", Long.class);
        String sql = "insert into platform.owner_invitation (owner_account_id, email, token_hash, expires_at) values (?, ?, ?, now() + interval '7 days')";
        jdbc.update(sql, acc, "owner@example.test", DIGEST_A);
        assertThat(refused(sql, acc, "owner@example.test", new byte[10])).isEqualTo(CHECK);
        assertThat(refused(sql, acc, "owner@example.test", DIGEST_A)).isEqualTo(UNIQUE);
        assertThat(refused(sql, acc, "owner@example.test", DIGEST_B)).as("one open invitation per account").isEqualTo(UNIQUE);
        jdbc.update("update platform.owner_invitation set revoked_at = now()");
        jdbc.update(sql, acc, "owner@example.test", DIGEST_B);
    }

    @Test
    void theRuntimeRoleGetsNothingInThePlatformSchemaAndOnlyTheIntendedIdentityPrivileges() {
        migrateToLatest();
        assertThat(jdbc.queryForObject("select has_schema_privilege('mandi_app', 'platform', 'USAGE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_schema_privilege('mandi_app', 'platform', 'CREATE')", Boolean.class)).isFalse();
        for (String t : jdbc.queryForList("select tablename from pg_tables where schemaname = 'platform'", String.class)) {
            for (String p : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'platform.' || ?, ?)", Boolean.class, t, p))
                        .as(p + " on platform." + t).isFalse();
            }
        }
        for (String t : List.of("app_user", "firm_member", "credential_token", "firm_invitation")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.' || ?, 'SELECT')", Boolean.class, t)).as(t).isTrue();
            assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.' || ?, 'DELETE')", Boolean.class, t)).as("no DELETE on " + t).isFalse();
            assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.' || ?, 'TRUNCATE')", Boolean.class, t)).as("no TRUNCATE on " + t).isFalse();
        }
    }

    // ---- rollback -----------------------------------------------------------------------------------------------

    private String publicSnapshot() {
        return jdbc.queryForObject("select string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable, ',' "
                + "order by table_name, column_name) from information_schema.columns where table_schema = 'public'", String.class)
                + "\nCONSTRAINTS " + jdbc.queryForObject("select string_agg(conrelid::regclass::text || '.' || conname || ':' || contype::text, ',' "
                + "order by conrelid::regclass::text, conname) from pg_constraint where connamespace = 'public'::regnamespace", String.class)
                + "\nINDEXES " + jdbc.queryForObject("select string_agg(indexdef, ',' order by indexname) from pg_indexes where schemaname = 'public'", String.class)
                + "\nTRIGGERS " + jdbc.queryForObject("select string_agg(tgname, ',' order by tgname) from pg_trigger where not tgisinternal", String.class)
                + "\nFUNCTIONS " + jdbc.queryForObject("select coalesce(string_agg(proname, ',' order by proname), '') from pg_proc where pronamespace = 'public'::regnamespace", String.class);
    }

    @Test
    void rollbackU6RestoresTheV5SchemaExactlyAndV6CanBeAppliedAgain() throws IOException {
        migrateTo("5");
        String v5 = publicSnapshot();
        migrateToLatest();
        assertThat(publicSnapshot()).isNotEqualTo(v5);

        runRollback("U13__remove_calculated_amounts.sql");

        runRollback("U12__remove_farmer_village.sql");
        runRollback("U11__remove_aadat_workflow.sql");
        runRollback("U10__remove_audit_actors_and_bill_idempotency.sql");
        runRollback("U9__remove_owner_invitation_acceptance.sql");
        runRollback("U8__remove_authentication.sql");
        runRollback("U7__remove_platform_boundary.sql");   // later migrations are rolled back first (their views read the V6 tables)
        assertThat(jdbc.queryForObject("select has_schema_privilege('public', 'public', 'USAGE')", Boolean.class)).as("U7 gives PUBLIC its default back").isTrue();
        runRollback("U6__remove_identity.sql");

        assertThat(publicSnapshot()).as("exactly the V5 schema again").isEqualTo(v5);
        assertThat(jdbc.queryForObject("select count(*) from pg_namespace where nspname = 'platform'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '6'", Integer.class)).isZero();
        migrateToLatest();                                                  // applies again
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.app_user', 'INSERT')", Boolean.class))
                .as("the runtime grants are applied again together with V6").isTrue();
    }

    @Test
    void rollbackU6RefusesWhenIdentityRowsExistAndChangesNothing() {
        migrateToLatest();
        user("real@example.test");
        assertThatThrownBy(() -> runRollback("U6__remove_identity.sql")).hasMessageContaining("U6 refused");
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_namespace where nspname = 'platform'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '6'", Integer.class)).isEqualTo(1);
    }
}
