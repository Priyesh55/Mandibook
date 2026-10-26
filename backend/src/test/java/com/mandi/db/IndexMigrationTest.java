package com.mandi.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/**
 * V5 (additive indexes) and its rollback U5, in throw-away databases. Synthetic data only.
 * V5 uses CREATE INDEX CONCURRENTLY, which PostgreSQL refuses inside a transaction: every migration run below
 * would fail if the migration were not configured with executeInTransaction=false.
 */
class IndexMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();

    /** index name, table, column list as PostgreSQL prints it (with the partial predicate where there is one). */
    private static final List<String[]> EXPECTED = List.of(
            new String[]{"idx_aadat_bill_firm_date", "aadat_bill", "(firm_id, bill_date DESC, id DESC)"},
            new String[]{"idx_aadat_bill_firm_farmer_date", "aadat_bill", "(firm_id, farmer_id, bill_date DESC, id DESC)"},
            new String[]{"idx_aadat_bill_village", "aadat_bill", "(village_id, firm_id) WHERE (village_id IS NOT NULL)"},
            new String[]{"idx_aadat_lot_item", "aadat_lot", "(item_id, firm_id)"},
            new String[]{"idx_aadat_lot_buyer", "aadat_lot", "(buyer_id, firm_id) WHERE (buyer_id IS NOT NULL)"},
            new String[]{"idx_aadat_expense_head", "aadat_expense", "(expense_head_id, firm_id)"},
            new String[]{"idx_farmer_firm_name", "farmer", "(firm_id, name, id)"},
            new String[]{"idx_buyer_firm_name", "buyer", "(firm_id, name, id)"},
            new String[]{"idx_village_firm_name", "village", "(firm_id, name, id)"},
            new String[]{"idx_item_firm_name", "item", "(firm_id, name, id)"},
            new String[]{"idx_expense_head_firm_name", "expense_head", "(firm_id, name, id)"});

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "idx_" + UUID.randomUUID().toString().replace("-", "");
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

    private void runRollback(String script) throws IOException {
        try (var in = getClass().getResourceAsStream("/db/rollback/" + script)) {
            assertThat(in).as(script + " is on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String schemaSnapshot() {
        return jdbc.queryForObject("select string_agg(table_name || '.' || column_name || ':' || data_type || ':' || is_nullable, ',' "
                + "order by table_name, column_name) from information_schema.columns where table_schema = 'public'", String.class)
                + "\nCONSTRAINTS " + jdbc.queryForObject("select string_agg(conrelid::regclass::text || '.' || conname || ':' || contype::text, ',' "
                + "order by conrelid::regclass::text, conname) from pg_constraint where connamespace = 'public'::regnamespace", String.class)
                + "\nINDEXES " + jdbc.queryForObject("select string_agg(indexdef, ',' order by indexname) from pg_indexes "
                + "where schemaname = 'public'", String.class)
                + "\nTRIGGERS " + jdbc.queryForObject("select string_agg(tgname, ',' order by tgname) from pg_trigger "
                + "where not tgisinternal", String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void seedRows() {
        long firm = jdbc.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        jdbc.update("insert into village (name, firm_id) values ('Idx Village', ?)", firm);
        jdbc.update("insert into farmer (name, firm_id) values ('Idx Farmer', ?)", firm);
        jdbc.update("insert into buyer (name, firm_id) values ('Idx Buyer', ?)", firm);
        jdbc.update("insert into expense_head (name, firm_id) values ('Idx Head', ?)", firm);
        long bill = jdbc.queryForObject("insert into aadat_bill (bill_date, farmer_id, village_id, calculation_status, rule_set_id, firm_id) "
                + "values ('2026-10-01', (select id from farmer limit 1), (select id from village limit 1), 'NOT_CALCULATED', 'NONE', ?) returning id", Long.class, firm);
        jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, pieces, weight, rate, buyer_id, firm_id) values (?, 1, (select id from item limit 1), 1, 1, 1, (select id from buyer limit 1), ?)", bill, firm);
        jdbc.update("insert into aadat_expense (bill_id, seq, expense_head_id, amount, firm_id) values (?, 1, (select id from expense_head limit 1), 1, ?)", bill, firm);
    }

    @Test
    void everyIndexIsCreatedValidAndReadyWithTheIntendedColumns() {
        migrateToLatest();
        for (String[] e : EXPECTED) {
            String def = jdbc.queryForObject("select indexdef from pg_indexes where schemaname = 'public' and indexname = ?", String.class, e[0]);
            assertThat(def).as(e[0]).contains("ON public." + e[1]).contains(e[2]);
            assertThat(jdbc.queryForObject("select i.indisvalid and i.indisready from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname = ?",
                    Boolean.class, e[0])).as(e[0] + " is valid and ready").isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from pg_index where not indisvalid or not indisready", Integer.class)).isZero();
    }

    @Test
    void noIndexDuplicatesAnotherOneOnTheSameTable() {
        migrateToLatest();
        List<String> dups = jdbc.queryForList("""
            select indrelid::regclass::text || ' ' || indkey::text
            from pg_index
            where indrelid in (select oid from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r')
            group by indrelid, indkey::text, indoption::text, coalesce(pg_get_expr(indpred, indrelid), '')
            having count(*) > 1""", String.class);
        assertThat(dups).as("identical duplicate indexes").isEmpty();
    }

    @Test
    void rollbackU5RemovesOnlyTheNewIndexesAndKeepsEveryRow() throws IOException {
        migrateTo("4");
        seedRows();
        String schemaBefore = schemaSnapshot();
        int bills = count("aadat_bill"), lots = count("aadat_lot"), expenses = count("aadat_expense");

        migrateTo("5");   // V5 specifically: later migrations (V6 identity) have their own rollback and tests
        assertThat(schemaSnapshot()).as("V5 really added indexes").isNotEqualTo(schemaBefore);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '5'", Integer.class)).isEqualTo(1);

        runRollback("U5__drop_additive_indexes.sql");

        assertThat(schemaSnapshot()).as("schema is exactly as before V5").isEqualTo(schemaBefore);
        assertThat(count("aadat_bill")).isEqualTo(bills);
        assertThat(count("aadat_lot")).isEqualTo(lots);
        assertThat(count("aadat_expense")).isEqualTo(expenses);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '5'", Integer.class)).isZero();

        migrateTo("5"); // can be applied again
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'idx_aadat_bill_firm_date'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rollingBackU5ThenU4RestoresTheV3Schema() throws IOException {
        migrateTo("3");
        String v3 = schemaSnapshot();
        migrateTo("5");   // V6 (identity) is rolled back by U6 and tested in IdentityMigrationTest
        runRollback("U5__drop_additive_indexes.sql");
        runRollback("U4__remove_firm.sql");
        assertThat(schemaSnapshot()).isEqualTo(v3);
    }

    @Test
    void aLeftoverInvalidIndexMakesTheMigrationFailInsteadOfBeingSilentlyAccepted() {
        migrateTo("5");
        // simulate what a failed CONCURRENTLY build leaves behind
        jdbc.update("update pg_index set indisvalid = false where indexrelid = 'idx_aadat_lot_item'::regclass");
        jdbc.update("delete from flyway_schema_history where version = '5'");
        assertThatThrownBy(() -> migrateTo("5")).hasMessageContaining("invalid index");
    }
}
