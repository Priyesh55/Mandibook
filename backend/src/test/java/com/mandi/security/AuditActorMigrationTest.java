package com.mandi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * V10 on throw-away PostgreSQL 16 databases: audit actors without touching history, and the idempotency columns of bills.
 * Synthetic data only.
 */
class AuditActorMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();
    private static final String HASH = "{bcrypt}$2a$12$" + "a".repeat(53);

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "aud_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + db);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).target(MigrationVersion.LATEST).load().migrate();
    }

    private void rollback() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/rollback/U13__remove_calculated_amounts.sql")) {   // latest migration first
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var in = getClass().getResourceAsStream("/db/rollback/U12__remove_farmer_village.sql")) {   // later migration first
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var in = getClass().getResourceAsStream("/db/rollback/U11__remove_aadat_workflow.sql")) {   // later migration first
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var in = getClass().getResourceAsStream("/db/rollback/U10__remove_audit_actors_and_bill_idempotency.sql")) {
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String state(Runnable r) {
        Throwable e = catchThrowable(r::run);
        assertThat(e).isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        assertThat(cause).isInstanceOf(PSQLException.class);
        return ((PSQLException) cause).getSQLState();
    }

    private long firm() {
        return jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
    }

    @Test
    void existingHistoryIsLeftExactlyAsItWasAndStaysAppendOnly() {
        migrateTo("9");
        long f = firm();
        jdbc.update("insert into audit_event (entity_type, entity_id, action, details, actor, firm_id) values ('FARMER', 1, 'CREATED', 'old row', 'anonymous', ?)", f);
        jdbc.update("insert into audit_event (entity_type, entity_id, action, details, actor, firm_id) values ('FARMER', 2, 'CREATED', 'old row two', 'anonymous', ?)", f);
        String before = jdbc.queryForObject("select string_agg(id || '|' || entity_type || '|' || entity_id || '|' || action || '|' || details || '|' || actor || '|' || created_at, ';' order by id) from audit_event", String.class);

        migrateToLatest();

        assertThat(jdbc.queryForObject("select string_agg(id || '|' || entity_type || '|' || entity_id || '|' || action || '|' || details || '|' || actor || '|' || created_at, ';' order by id) from audit_event", String.class))
                .as("no existing row changed").isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where actor_user_id is not null or actor_membership_id is not null or actor_role is not null or request_id is not null", Integer.class))
                .as("old events are not attributed, guessed or backfilled").isZero();
        assertThat(state(() -> jdbc.update("update audit_event set details = 'x'"))).isEqualTo("P0001");
        assertThat(state(() -> jdbc.update("delete from audit_event"))).isEqualTo("P0001");
        assertThat(state(() -> jdbc.execute("truncate audit_event"))).isEqualTo("P0001");
    }

    @Test
    void anAuditActorMustBeARealPersonAndAMembershipOfThatSameFirm() {
        migrateToLatest();
        long f = firm();
        long other = jdbc.queryForObject("insert into firm (code, name) values ('AUD-OTHER', 'Other') returning id", Long.class);
        long user = jdbc.queryForObject("insert into app_user (email, password_hash) values ('actor@example.test', ?) returning id", Long.class, HASH);
        long member = jdbc.queryForObject("insert into firm_member (firm_id, user_id, role) values (?, ?, 'OWNER') returning id", Long.class, f, user);
        String sql = "insert into audit_event (entity_type, entity_id, action, actor, firm_id, actor_user_id, actor_membership_id, actor_role, request_id) values ('X', 1, 'A', 'user:1', ?, ?, ?, ?, ?)";
        jdbc.update(sql, f, user, member, "OWNER", UUID.randomUUID());
        assertThat(state(() -> jdbc.update(sql, other, user, member, "OWNER", UUID.randomUUID()))).as("membership of another firm").isEqualTo("23503");
        assertThat(state(() -> jdbc.update(sql, f, 999999L, member, "OWNER", UUID.randomUUID()))).as("unknown user").isEqualTo("23503");
        assertThat(state(() -> jdbc.update(sql, f, user, member, "PLATFORM_ADMIN", UUID.randomUUID()))).as("a platform administrator is not a business role").isEqualTo("23514");
        assertThat(state(() -> jdbc.update("delete from firm_member where id = ?", member))).as("the actor's membership can never disappear").isEqualTo("23514");
    }

    @Test
    void billIdempotencyColumnsAreConstrained() {
        migrateToLatest();
        long f = firm();
        long farmer = jdbc.queryForObject("insert into farmer (name, firm_id) values ('F', ?) returning id", Long.class, f);
        String sql = "insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id, idempotency_key, request_hash) values ('2026-10-01', ?, 'NOT_CALCULATED', 'NONE', ?, ?, ?)";
        jdbc.update(sql, farmer, f, "valid-key-0001", new byte[32]);
        assertThat(state(() -> jdbc.update(sql, farmer, f, "valid-key-0001", new byte[32]))).as("duplicate key in a firm").isEqualTo("23505");
        assertThat(state(() -> jdbc.update(sql, farmer, f, "bad key!", new byte[32]))).isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, farmer, f, "short", new byte[32]))).isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, farmer, f, "valid-key-0002", new byte[10]))).as("not a SHA-256").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, farmer, f, "valid-key-0003", null))).as("a key needs its fingerprint").isEqualTo("23514");
        jdbc.update("insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id) values ('2026-10-01', ?, 'NOT_CALCULATED', 'NONE', ?)", farmer, f);
    }

    @Test
    void rollbackU10RefusesWhileAttributionExistsAndOtherwiseRestoresTheV9Columns() throws IOException {
        migrateToLatest();
        long f = firm();
        long user = jdbc.queryForObject("insert into app_user (email, password_hash) values ('rb@example.test', ?) returning id", Long.class, HASH);
        jdbc.update("insert into audit_event (entity_type, entity_id, action, actor, firm_id, actor_user_id) values ('X', 1, 'A', 'user:1', ?, ?)", f, user);
        assertThatThrownBy(this::rollback).hasMessageContaining("U10 refused");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '10'", Integer.class)).isEqualTo(1);

        freshDatabase();
        migrateTo("9");
        String v9 = jdbc.queryForObject("select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns where table_schema = 'public' and table_name in ('audit_event', 'aadat_bill')", String.class);
        migrateToLatest();
        rollback();
        assertThat(jdbc.queryForObject("select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns where table_schema = 'public' and table_name in ('audit_event', 'aadat_bill')", String.class)).isEqualTo(v9);
        migrateToLatest();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '10'", Integer.class)).isEqualTo(1);
    }
}
