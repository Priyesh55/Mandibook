package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/** V8 (sessions, security event log, login throttle) and its rollback U8 in throw-away PostgreSQL 16 databases. Synthetic data only. */
class AuthenticationMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();
    private static final String HASH = "{bcrypt}$2a$12$" + "a".repeat(53);

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "auth_" + UUID.randomUUID().toString().replace("-", "");
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
            assertThat(in).as(script).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String sqlState(Runnable r) {
        Throwable e = catchThrowable(r::run);
        assertThat(e).isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        assertThat(cause).isInstanceOf(PSQLException.class);
        return ((PSQLException) cause).getSQLState();
    }

    private byte[] digest(char c) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) c);
        return b;
    }

    @Test
    void migratesFromV7AndFreshAndKeepsTheIdentityRows() {
        migrateTo("7");
        long u = jdbc.queryForObject("insert into app_user (email, password_hash) values ('keep@example.test', ?) returning id", Long.class, HASH);
        migrateToLatest();
        assertThat(jdbc.queryForObject("select count(*) from app_user where id = ?", Integer.class, u)).isEqualTo(1);
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' and tablename in "
                + "('spring_session', 'spring_session_attributes', 'auth_event', 'auth_throttle') order by 1", String.class))
                .containsExactly("auth_event", "auth_throttle", "spring_session", "spring_session_attributes");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '8' and success", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSecurityEventLogIsAppendOnlyAndStoresOnlyKeyedHashes() {
        migrateToLatest();
        long u = jdbc.queryForObject("insert into app_user (email, password_hash) values ('log@example.test', ?) returning id", Long.class, HASH);
        jdbc.update("insert into auth_event (event_type, user_id, email_hmac, ip_hmac) values ('LOGIN_FAILURE', ?, ?, ?)", u, digest('e'), digest('i'));
        assertThat(sqlState(() -> jdbc.update("update auth_event set details = 'x'"))).isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("delete from auth_event"))).isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.execute("truncate auth_event"))).isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("insert into auth_event (event_type) values ('LOGIN_FAILED_OTHER')"))).as("unknown type").isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("insert into auth_event (event_type, email_hmac) values ('LOGIN_FAILURE', ?)", new byte[20]))).as("not a 32-byte hash").isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("insert into auth_event (event_type, user_id) values ('LOGIN_FAILURE', 999999)"))).isEqualTo("23503");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'auth_event' and column_name ~ '(password|token|secret|session)'", String.class))
                .as("no column that could hold a secret or a session id").isEmpty();
    }

    @Test
    void theThrottleKeysAreHashesAndCountersCannotBeNegative() {
        migrateToLatest();
        jdbc.update("insert into auth_throttle (key_hmac, window_start, attempts) values (?, now(), 1)", digest('k'));
        assertThat(sqlState(() -> jdbc.update("insert into auth_throttle (key_hmac, window_start, attempts) values (?, now(), 1)", digest('k')))).isEqualTo("23505");
        assertThat(sqlState(() -> jdbc.update("insert into auth_throttle (key_hmac, window_start, attempts) values (?, now(), 1)", "plain-email@example.test".getBytes()))).isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("insert into auth_throttle (key_hmac, window_start, attempts) values (?, now(), -1)", digest('z')))).isEqualTo("23514");
    }

    @Test
    void theSessionTablesAreTheLibrarysAndSessionAttributesFollowTheirSession() {
        migrateToLatest();
        jdbc.update("insert into spring_session values ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 1, 1, 1800, 2, 'u:1')");
        jdbc.update("insert into spring_session_attributes values ('11111111-1111-1111-1111-111111111111', 'a', ?)", new byte[] {1});
        jdbc.update("delete from spring_session");
        assertThat(jdbc.queryForObject("select count(*) from spring_session_attributes", Integer.class)).as("cascade").isZero();
    }

    @Test
    void thePlatformRoleHasNothingOnTheNewTablesAndTheRuntimeRoleHasExactlyTheDecidedPrivileges() {
        migrateToLatest();
        for (String t : List.of("spring_session", "spring_session_attributes", "auth_event", "auth_throttle")) {
            for (String p : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(jdbc.queryForObject("select has_table_privilege(?, 'public.' || ?, ?)", Boolean.class, TestcontainersConfiguration.PLATFORM_ROLE, t, p))
                        .as("platform role: " + p + " on " + t).isFalse();
            }
        }
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.spring_session', 'DELETE')", Boolean.class)).as("sessions are deleted by the store").isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.auth_event', 'DELETE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.auth_event', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.auth_event', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select has_schema_privilege('mandi_app', 'public', 'CREATE')", Boolean.class)).as("Spring cannot create session tables itself").isFalse();
    }

    @Test
    void rollbackU8RemovesTheTablesAndRefusesWhileTheSecurityLogHasRows() throws IOException {
        migrateToLatest();
        jdbc.update("insert into auth_event (event_type) values ('LOGOUT')");
        assertThatThrownBy(() -> runRollback("U8__remove_authentication.sql")).hasMessageContaining("U8 refused");   // the refusal comes before anything is dropped
        assertThat(jdbc.queryForObject("select count(*) from auth_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '8'", Integer.class)).isEqualTo(1);

        // with an empty log (for example a database that never signed anybody in) it works and V8 can be applied again
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db2 = "auth_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db2);
        url = PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + db2 + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
        migrateToLatest();
        runRollback("U13__remove_calculated_amounts.sql");
        runRollback("U12__remove_farmer_village.sql");
        runRollback("U11__remove_aadat_workflow.sql");
        runRollback("U10__remove_audit_actors_and_bill_idempotency.sql");   // later migrations first
        runRollback("U9__remove_owner_invitation_acceptance.sql");
        runRollback("U8__remove_authentication.sql");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public' and tablename in "
                + "('spring_session', 'spring_session_attributes', 'auth_event', 'auth_throttle')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '8'", Integer.class)).isZero();
        migrateToLatest();
        assertThat(jdbc.queryForObject("select has_table_privilege('mandi_app', 'public.spring_session', 'INSERT')", Boolean.class))
                .as("the runtime grants are applied again with V8").isTrue();
    }
}
