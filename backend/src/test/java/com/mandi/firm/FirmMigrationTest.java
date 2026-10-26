package com.mandi.firm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/**
 * Migration V4 (firm ownership) on a throwaway database: data created under V3 survives, is backfilled to the
 * DEFAULT firm, the database itself rejects cross-firm references, and the rollback script restores the exact
 * pre-V4 schema without losing a row. Synthetic data only.
 */
class FirmMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();
    private static final String[] TABLES = {"farmer", "buyer", "village", "item", "expense_head",
            "aadat_bill", "aadat_lot", "aadat_expense", "audit_event"};

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "mig_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + db + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(MigrationVersion.LATEST).load().migrate();
    }

    private void rollback() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/rollback/U4__remove_firm.sql")) {
            assertThat(in).as("rollback script is on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Synthetic rows as they exist in a database that is still at V3. Returns the bill id. */
    private long seedAtV3() {
        jdbc.update("insert into village (name) values ('Pre Village')");
        jdbc.update("insert into farmer (name, name_hi) values ('Pre Farmer', 'प्री किसान')");
        jdbc.update("insert into buyer (name) values ('Pre Buyer')");
        jdbc.update("insert into expense_head (name) values ('Pre Expense')");
        long bill = jdbc.queryForObject("""
            insert into aadat_bill (bill_date, farmer_id, village_id, vehicle_no, calculation_status, rule_set_id)
            values ('2026-10-01', (select id from farmer limit 1), (select id from village limit 1), 'PRE-1',
                    'NOT_CALCULATED', 'NONE') returning id""", Long.class);
        jdbc.update("""
            insert into aadat_lot (bill_id, line_no, item_id, pieces, weight, rate, buyer_id)
            values (?, 1, (select id from item where name = 'Onion'), 3, 30.5, 2, (select id from buyer limit 1))""", bill);
        jdbc.update("insert into aadat_expense (bill_id, seq, expense_head_id, amount) values (?, 1, (select id from expense_head limit 1), 7)", bill);
        jdbc.update("insert into audit_event (entity_type, entity_id, action, details) values ('AADAT_BILL', ?, 'CREATED', 'pre-V4 history row one')", bill);
        jdbc.update("insert into audit_event (entity_type, entity_id, action, details) values ('FARMER', 1, 'CREATED', 'pre-V4 history row two')");
        return bill;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String auditFingerprint() {
        return jdbc.queryForObject("""
            select md5(string_agg(id || '|' || entity_type || '|' || entity_id || '|' || action || '|'
                       || coalesce(details, '') || '|' || actor || '|' || created_at::text, ';' order by id))
            from audit_event""", String.class);
    }

    private String schemaSnapshot() {
        return jdbc.queryForObject("select string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable, ',' "
                + "order by table_name, column_name) from information_schema.columns where table_schema = 'public'", String.class)
                + "\nCONSTRAINTS " + jdbc.queryForObject("select string_agg(conrelid::regclass::text || '.' || conname || ':' || contype::text, ',' "
                + "order by conrelid::regclass::text, conname) from pg_constraint where connamespace = 'public'::regnamespace", String.class)
                + "\nINDEXES " + jdbc.queryForObject("select string_agg(indexname, ',' order by indexname) from pg_indexes "
                + "where schemaname = 'public'", String.class)
                + "\nTRIGGERS " + jdbc.queryForObject("select string_agg(tgname, ',' order by tgname) from pg_trigger "
                + "where not tgisinternal", String.class);
    }

    @Test
    void existingDataSurvivesAndIsBackfilledToTheDefaultFirm() {
        migrateTo("3");
        seedAtV3();
        int[] before = new int[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) before[i] = count(TABLES[i]);
        String auditBefore = auditFingerprint();

        migrateToLatest();

        long defaultFirm = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        assertThat(count("firm")).isEqualTo(1);
        for (int i = 0; i < TABLES.length; i++) {
            assertThat(count(TABLES[i])).as("row count of " + TABLES[i]).isEqualTo(before[i]);
            assertThat(jdbc.queryForObject("select count(*) from " + TABLES[i] + " where firm_id is distinct from ?",
                    Integer.class, defaultFirm)).as("rows of " + TABLES[i] + " not in the default firm").isZero();
        }
        assertThat(auditFingerprint()).as("audit history is byte-for-byte unchanged").isEqualTo(auditBefore);

        // The append-only protection survived the migration.
        assertThatThrownBy(() -> jdbc.update("update audit_event set details = 'x'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        // New rows must name their firm: the backfill default was dropped.
        assertThatThrownBy(() -> jdbc.update("insert into farmer (name) values ('no firm')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseItselfRefusesCrossFirmReferences() {
        migrateToLatest();
        long a = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        long b = jdbc.queryForObject("insert into firm (code, name) values ('B', 'Firm B') returning id", Long.class);

        long farmerA = jdbc.queryForObject("insert into farmer (name, firm_id) values ('FA', ?) returning id", Long.class, a);
        long farmerB = jdbc.queryForObject("insert into farmer (name, firm_id) values ('FB', ?) returning id", Long.class, b);
        long villageB = jdbc.queryForObject("insert into village (name, firm_id) values ('VB', ?) returning id", Long.class, b);
        long itemA = jdbc.queryForObject("insert into item (name, firm_id) values ('IA', ?) returning id", Long.class, a);
        long itemB = jdbc.queryForObject("insert into item (name, firm_id) values ('IB', ?) returning id", Long.class, b);
        long buyerB = jdbc.queryForObject("insert into buyer (name, firm_id) values ('BB', ?) returning id", Long.class, b);
        long headB = jdbc.queryForObject("insert into expense_head (name, firm_id) values ('HB', ?) returning id", Long.class, b);

        String insertBill = "insert into aadat_bill (bill_date, farmer_id, village_id, calculation_status, rule_set_id, firm_id) "
                + "values ('2026-10-01', ?, ?, 'NOT_CALCULATED', 'NONE', ?) returning id";

        // positive control: same-firm references work
        long billA = jdbc.queryForObject(insertBill, Long.class, farmerA, null, a);
        jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, firm_id) values (?, 1, ?, ?)", billA, itemA, a);

        // a bill of firm A cannot use a farmer or a village of firm B
        assertThatThrownBy(() -> jdbc.queryForObject(insertBill, Long.class, farmerB, null, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.queryForObject(insertBill, Long.class, farmerA, villageB, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        // a bill of firm B cannot use a farmer of firm A
        assertThatThrownBy(() -> jdbc.queryForObject(insertBill, Long.class, farmerA, null, b))
                .isInstanceOf(DataIntegrityViolationException.class);
        // lots and expenses cannot cross firms through the bill, item, buyer or expense head
        assertThatThrownBy(() -> jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, firm_id) values (?, 2, ?, ?)", billA, itemB, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, buyer_id, firm_id) values (?, 3, ?, ?, ?)", billA, itemA, buyerB, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, firm_id) values (?, 4, ?, ?)", billA, itemB, b))
                .as("lot of firm B pointing at a bill of firm A").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into aadat_expense (bill_id, seq, expense_head_id, amount, firm_id) values (?, 1, ?, 5, ?)", billA, headB, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("aadat_bill")).isEqualTo(1);
        assertThat(count("aadat_lot")).isEqualTo(1);
    }

    @Test
    void rollbackRestoresTheExactPreV4SchemaAndKeepsEveryRow() throws IOException {
        migrateTo("3");
        seedAtV3();
        String schemaBefore = schemaSnapshot();
        int[] before = new int[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) before[i] = count(TABLES[i]);
        String auditBefore = auditFingerprint();

        // U4 undoes exactly V4, so it is run from the V4 state. Later migrations have their own rollback scripts
        // (U5 first, then U4: see IndexMigrationTest.rollingBackU5ThenU4RestoresTheV3Schema).
        migrateTo("4");
        assertThat(schemaSnapshot()).as("V4 really changed the schema").isNotEqualTo(schemaBefore);

        rollback();

        assertThat(schemaSnapshot()).as("schema (columns, constraints, indexes, triggers) is exactly as before V4")
                .isEqualTo(schemaBefore);
        for (int i = 0; i < TABLES.length; i++) {
            assertThat(count(TABLES[i])).as("row count of " + TABLES[i]).isEqualTo(before[i]);
        }
        assertThat(auditFingerprint()).as("audit history untouched by the rollback").isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '4'", Integer.class)).isZero();

        // After a rollback the migration can be applied again, and backfills again.
        migrateToLatest();
        assertThat(count("firm")).isEqualTo(1);
        long defaultFirm = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill where firm_id = ?", Integer.class, defaultFirm))
                .isEqualTo(before[5]);
    }

    @Test
    void rollbackIsRefusedOnceASecondFirmExists() {
        migrateToLatest();
        jdbc.update("insert into firm (code, name) values ('SECOND', 'Second firm')");
        assertThatThrownBy(this::rollback).hasMessageContaining("rollback refused");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'firm'", Integer.class))
                .as("the firm boundary is still in place").isEqualTo(1);
    }
}
