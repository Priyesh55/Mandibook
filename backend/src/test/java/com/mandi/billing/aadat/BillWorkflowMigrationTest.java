package com.mandi.billing.aadat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mandi.TestcontainersConfiguration;

/**
 * V11 (purchaser allocations, header fields, bill number counter, financial year, firm print profile) on throw-away
 * PostgreSQL 16 databases. Synthetic data only.
 */
class BillWorkflowMigrationTest {

    private static final PostgreSQLContainer PG = TestcontainersConfiguration.postgres();

    private String url;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String db = "wf_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + db);
        url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + db);
        ds = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    private void migrate(MigrationVersion target) {
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).target(target).load().migrate();
    }

    private void migrateTo(String version) {
        migrate(MigrationVersion.fromVersion(version));
    }

    private void migrateToLatest() {
        migrate(MigrationVersion.LATEST);
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
        try (var in = getClass().getResourceAsStream("/db/rollback/U11__remove_aadat_workflow.sql")) {
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

    private long master(String table, String name, long firm) {
        return jdbc.queryForObject("insert into " + table + " (name, firm_id) values (?, ?) returning id", Long.class, name, firm);
    }

    private long bill(long firm, long farmer, String date) {
        return jdbc.queryForObject("insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id) "
                + "values (?::date, ?, 'NOT_CALCULATED', 'NONE', ?) returning id", Long.class, date, farmer, firm);
    }

    private long lot(long firm, long bill, int line, long item) {
        return jdbc.queryForObject("insert into aadat_lot (bill_id, line_no, item_id, firm_id, pieces, rate) values (?, ?, ?, ?, 5, 100) returning id",
                Long.class, bill, line, item, firm);
    }

    private static final String OLD_COLUMNS = "select string_agg(b.id || '|' || b.bill_date || '|' || b.farmer_id || '|' || coalesce(b.village_id::text, '-') || '|' "
            + "|| coalesce(b.vehicle_no, '-') || '|' || b.calculation_status || '|' || b.rule_set_id || '|' || b.firm_id || '|' || b.created_at, ';' order by b.id) from aadat_bill b";
    private static final String OLD_LOTS = "select string_agg(id || '|' || bill_id || '|' || line_no || '|' || item_id || '|' || coalesce(pieces::text, '-') "
            + "|| '|' || coalesce(weight::text, '-') || '|' || coalesce(rate::text, '-') || '|' || coalesce(buyer_id::text, '-'), ';' order by id) from aadat_lot";

    @Test
    void existingBillsLotsAndExpensesAreUnchangedAndGetTheirFinancialYear() {
        migrateTo("10");
        long f = firm();
        long farmer = master("farmer", "Synthetic Farmer", f);
        long item = master("item", "Synthetic Item", f);
        long buyer = master("buyer", "Synthetic Buyer", f);
        long head = master("expense_head", "Synthetic Head", f);
        long march = bill(f, farmer, "2026-03-31");
        long april = bill(f, farmer, "2026-04-01");
        long december = bill(f, farmer, "2026-12-31");
        long l = lot(f, march, 1, item);
        jdbc.update("update aadat_lot set buyer_id = ? where id = ?", buyer, l);
        jdbc.update("insert into aadat_expense (bill_id, seq, expense_head_id, amount, firm_id) values (?, 1, ?, 12.5, ?)", march, head, f);
        String bills = jdbc.queryForObject(OLD_COLUMNS, String.class);
        String lots = jdbc.queryForObject(OLD_LOTS, String.class);

        migrateToLatest();

        assertThat(jdbc.queryForObject(OLD_COLUMNS, String.class)).as("no existing bill column changed").isEqualTo(bills);
        assertThat(jdbc.queryForObject(OLD_LOTS, String.class)).as("no existing lot changed, the old single buyer is kept").isEqualTo(lots);
        assertThat(jdbc.queryForObject("select amount from aadat_expense where bill_id = ?", java.math.BigDecimal.class, march)).isEqualByComparingTo("12.5");
        assertThat(jdbc.queryForObject("select fy_start_year from aadat_bill where id = ?", Integer.class, march)).as("31 March belongs to the year that began the April before").isEqualTo(2025);
        assertThat(jdbc.queryForObject("select fy_start_year from aadat_bill where id = ?", Integer.class, april)).as("1 April starts the new year").isEqualTo(2026);
        assertThat(jdbc.queryForObject("select fy_start_year from aadat_bill where id = ?", Integer.class, december)).isEqualTo(2026);
        assertThat(jdbc.queryForObject("select count(*) from aadat_bill where bill_no is not null or transport is not null or bill_type is not null", Integer.class))
                .as("old bills get no invented number or header value").isZero();
        assertThat(jdbc.queryForObject("select count(*) from aadat_lot_buyer", Integer.class)).as("no purchaser row is invented").isZero();
        assertThat(state(() -> jdbc.update("update aadat_bill set fy_start_year = 1999"))).as("financial year is generated, never written").isEqualTo("428C9");
    }

    @Test
    void aPurchaserRowCanOnlyReachItsOwnLotBillAndFirmAndMustBeSane() {
        migrateToLatest();
        long f = firm();
        long other = jdbc.queryForObject("insert into firm (code, name) values ('WF-OTHER', 'Other') returning id", Long.class);
        long farmer = master("farmer", "F", f);
        long item = master("item", "I", f);
        long buyer = master("buyer", "B", f);
        long foreignBuyer = master("buyer", "Foreign", other);
        long b1 = bill(f, farmer, "2026-10-01");
        long b2 = bill(f, farmer, "2026-10-02");
        long lot1 = lot(f, b1, 1, item);
        String sql = "insert into aadat_lot_buyer (firm_id, bill_id, lot_id, seq, buyer_id, haste, pieces, weight, rate, value, commission_applies, commission_percent, commission_amount) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql, f, b1, lot1, 1, buyer, "Synthetic Person", 3, 120, 1800, 2160, true, 5, 108);
        jdbc.update(sql, f, b1, lot1, 2, buyer, null, null, null, null, null, false, null, null);   // nothing is mandatory except the buyer
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 1, buyer, null, 1, 1, 1, 1, false, null, null))).as("same (lot, seq)").isEqualTo("23505");
        assertThat(state(() -> jdbc.update(sql, f, b2, lot1, 3, buyer, null, 1, 1, 1, 1, false, null, null))).as("lot of another bill").isEqualTo("23503");
        assertThat(state(() -> jdbc.update(sql, other, b1, lot1, 3, buyer, null, 1, 1, 1, 1, false, null, null))).as("bill of another firm").isEqualTo("23503");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, foreignBuyer, null, 1, 1, 1, 1, false, null, null))).as("buyer of another firm").isEqualTo("23503");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, -1, 1, 1, 1, false, null, null))).as("negative pieces").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, -1, 1, 1, false, null, null))).as("negative weight").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, 1, -1, 1, false, null, null))).as("negative rate").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, 1, 1, -1, false, null, null))).as("negative value").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, 1, 1, 1, true, 101, 1))).as("percent above 100").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, 1, 1, 1, true, 5, -1))).as("negative commission").isEqualTo("23514");
        assertThat(state(() -> jdbc.update(sql, f, b1, lot1, 3, buyer, null, 1, 1, 1, 1, false, 5, null))).as("percent without a commission").isEqualTo("23514");
        assertThat(state(() -> jdbc.update("delete from aadat_lot where id = ?", lot1))).as("a lot with purchasers cannot disappear").isEqualTo("23503");
    }

    @Test
    void headerFieldsAndNumbersAreConstrainedAndTheNumberIsUniquePerFirm() {
        migrateToLatest();
        long f = firm();
        long other = jdbc.queryForObject("insert into firm (code, name) values ('WF-OTHER2', 'Other') returning id", Long.class);
        long farmer = master("farmer", "F", f);
        long farmerOther = master("farmer", "F2", other);
        long b1 = bill(f, farmer, "2026-10-01");
        long b2 = bill(f, farmer, "2026-10-02");
        long bo = bill(other, farmerOther, "2026-10-02");
        jdbc.update("update aadat_bill set bill_no = 1 where id = ?", b1);
        jdbc.update("update aadat_bill set bill_no = 1 where id = ?", bo);                       // another firm may use number 1
        assertThat(state(() -> jdbc.update("update aadat_bill set bill_no = 1 where id = ?", b2))).as("one number once per firm").isEqualTo("23505");
        assertThat(state(() -> jdbc.update("update aadat_bill set bill_no = 0 where id = ?", b2))).isEqualTo("23514");
        assertThat(state(() -> jdbc.update("update aadat_bill set bill_type = 'LOAN' where id = ?", b2))).as("only credit or cash").isEqualTo("23514");
        assertThat(state(() -> jdbc.update("update aadat_bill set bhada_amount = -1, bhada_paid = true where id = ?", b2))).isEqualTo("23514");
        assertThat(state(() -> jdbc.update("update aadat_bill set bhada_amount = 10 where id = ?", b2))).as("an amount needs bhada paid").isEqualTo("23514");
        jdbc.update("update aadat_bill set bhada_paid = true, bhada_amount = 10, bill_type = 'CASH', transport = 'T', panjiyan_no = 'P', book_no = 'B', receipt_no = 'R', entry_no = 'E', ws_no = 'W' where id = ?", b2);
        assertThat(state(() -> jdbc.update("insert into aadat_lot (bill_id, line_no, item_id, firm_id, lot_value) values (?, 9, 1, ?, -1)", b2, f))).isIn("23514", "23503");
        jdbc.update("update firm set address = 'A', phone = '1', licence_no = 'L', shop_no = 'S' where id = ?", f);
    }

    @Test
    void theBillNumberCounterHandsOutDistinctConsecutiveNumbersUnderConcurrencyAndRollsBackWithAFailedSave() throws Exception {
        migrateToLatest();
        long f = firm();
        long other = jdbc.queryForObject("insert into firm (code, name) values ('WF-OTHER3', 'Other') returning id", Long.class);
        DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                final long firmId = i % 2 == 0 ? f : other;
                jobs.add(() -> new TransactionTemplate(tm).execute(tx -> allocate(tm, firmId)));
            }
            List<Integer> forFirm = new ArrayList<>();
            List<Integer> forOther = new ArrayList<>();
            int i = 0;
            for (Future<Integer> fut : pool.invokeAll(jobs)) {
                (i++ % 2 == 0 ? forFirm : forOther).add(fut.get());
            }
            assertThat(new TreeSet<>(forFirm)).as("distinct").hasSize(12).first().isEqualTo(1);
            assertThat(new TreeSet<>(forFirm).last()).as("consecutive, no gap").isEqualTo(12);
            assertThat(new TreeSet<>(forOther)).hasSize(12);
            assertThat(new TreeSet<>(forOther).last()).as("another firm has its own series").isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }
        // a transaction that fails after taking a number gives it back
        int[] taken = new int[1];
        assertThatThrownBy(() -> new TransactionTemplate(tm).executeWithoutResult(tx -> {
            taken[0] = allocate(tm, f);
            throw new IllegalStateException("simulated failure after the number was taken");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select next_no from bill_number_counter where firm_id = ?", Integer.class, f)).as("number returned").isEqualTo(taken[0]);
    }

    /** The statements the application runs inside the save transaction (see BillNumberService). */
    private static int allocate(DataSourceTransactionManager tm, long firmId) {
        // same connection as the surrounding transaction: the transaction manager binds it to the thread
        JdbcTemplate bound = new JdbcTemplate(tm.getDataSource());
        bound.update("insert into bill_number_counter (firm_id, next_no) values (?, 1) on conflict (firm_id) do nothing", firmId);
        int no = bound.queryForObject("select next_no from bill_number_counter where firm_id = ? for update", Integer.class, firmId);
        bound.update("update bill_number_counter set next_no = next_no + 1 where firm_id = ?", firmId);
        return no;
    }

    @Test
    void rollbackU11RefusesWhileAnyNewDataExistsAndOtherwiseRestoresTheV10Shape() throws IOException {
        migrateToLatest();
        long f = firm();
        jdbc.update("update firm set licence_no = 'L-1' where id = ?", f);
        assertThatThrownBy(this::rollback).hasMessageContaining("U11 refused");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '11'", Integer.class)).isEqualTo(1);

        freshDatabase();
        migrateTo("10");
        String v10 = shape();
        migrateToLatest();
        rollback();
        assertThat(shape()).as("the V10 table shape is restored exactly").isEqualTo(v10);
        migrateToLatest();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '11'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rollbackU11AlsoRefusesForPurchaserRowsNumbersAndCounters() {
        migrateToLatest();
        long f = firm();
        long farmer = master("farmer", "F", f);
        long item = master("item", "I", f);
        long buyer = master("buyer", "B", f);
        long b = bill(f, farmer, "2026-10-01");
        long l = lot(f, b, 1, item);
        jdbc.update("insert into aadat_lot_buyer (firm_id, bill_id, lot_id, seq, buyer_id) values (?, ?, ?, 1, ?)", f, b, l, buyer);
        assertThatThrownBy(this::rollback).hasMessageContaining("U11 refused").hasMessageContaining("1 purchaser rows");
    }

    private String shape() {
        return jdbc.queryForObject("select string_agg(table_name || '.' || column_name || ':' || data_type, ',' order by table_name, column_name) "
                + "from information_schema.columns where table_schema = 'public'", String.class);
    }
}
