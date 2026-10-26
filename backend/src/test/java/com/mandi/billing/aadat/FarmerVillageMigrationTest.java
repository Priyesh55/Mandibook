package com.mandi.billing.aadat;

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

/** V12 (a farmer belongs to a village) on throw-away PostgreSQL 16 databases. Synthetic data only. */
class FarmerVillageMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "fv_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + db);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private void migrate(MigrationVersion target) {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).target(target).load().migrate();
    }

    private void rollback() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/rollback/U13__remove_calculated_amounts.sql")) {   // latest migration first
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var in = getClass().getResourceAsStream("/db/rollback/U12__remove_farmer_village.sql")) {
            assertThat(in).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String state(Runnable r) {
        Throwable e = catchThrowable(r::run);
        assertThat(e).as("the database should have refused").isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        assertThat(cause).isInstanceOf(PSQLException.class);
        return ((PSQLException) cause).getSQLState();
    }

    private long firm() {
        return jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
    }

    @Test
    void existingFarmersKeepAnEmptyVillageAndTheKeyRefusesAnotherFirmsVillage() {
        migrate(MigrationVersion.fromVersion("11"));
        long f = firm();
        jdbc.update("insert into farmer (name, firm_id) values ('Old Farmer', ?)", f);
        String before = jdbc.queryForObject("select string_agg(id || '|' || name || '|' || active, ';' order by id) from farmer", String.class);

        migrate(MigrationVersion.LATEST);

        assertThat(jdbc.queryForObject("select string_agg(id || '|' || name || '|' || active, ';' order by id) from farmer", String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from farmer where village_id is not null", Integer.class)).as("nothing is guessed").isZero();
        long other = jdbc.queryForObject("insert into firm (code, name) values ('FV-OTHER', 'Other') returning id", Long.class);
        long mine = jdbc.queryForObject("insert into village (name, firm_id) values ('Mine', ?) returning id", Long.class, f);
        long foreign = jdbc.queryForObject("insert into village (name, firm_id) values ('Foreign', ?) returning id", Long.class, other);
        jdbc.update("insert into farmer (name, firm_id, village_id) values ('New Farmer', ?, ?)", f, mine);
        assertThat(state(() -> jdbc.update("insert into farmer (name, firm_id, village_id) values ('Bad', ?, ?)", f, foreign))).isEqualTo("23503");
        assertThat(state(() -> jdbc.update("delete from village where id = ?", mine))).as("a village with farmers cannot disappear").isEqualTo("23503");
    }

    @Test
    void rollbackU12RefusesWhileAFarmerHasAVillageAndOtherwiseRestoresTheShape() throws IOException {
        migrate(MigrationVersion.LATEST);
        long f = firm();
        long village = jdbc.queryForObject("insert into village (name, firm_id) values ('V', ?) returning id", Long.class, f);
        jdbc.update("insert into farmer (name, firm_id, village_id) values ('F', ?, ?)", f, village);
        assertThatThrownBy(this::rollback).hasMessageContaining("U12 refused");

        freshDatabase();
        migrate(MigrationVersion.fromVersion("11"));
        String v11 = jdbc.queryForObject("select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns where table_schema = 'public'", String.class);
        migrate(MigrationVersion.LATEST);
        rollback();
        assertThat(jdbc.queryForObject("select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns where table_schema = 'public'", String.class)).isEqualTo(v11);
        migrate(MigrationVersion.LATEST);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '12'", Integer.class)).isEqualTo(1);
    }
}
