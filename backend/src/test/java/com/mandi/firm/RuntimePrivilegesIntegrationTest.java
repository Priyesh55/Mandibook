package com.mandi.firm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mandi.TestcontainersConfiguration;
import com.mandi.shared.DatabaseRoleGuard;

/**
 * What the restricted runtime role {@code mandi_app} can and cannot do. Every statement below is executed over a
 * real connection as that role, never through the owner connection. Synthetic data only.
 *
 * <p>If a new table or privilege is added, this test fails until {@code R__runtime_grants.sql} and the expected
 * matrix here are changed together: runtime access is always a conscious decision.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RuntimePrivilegesIntegrationTest {

    /** Exactly what the application needs; everything not listed must be denied. */
    private static final Map<String, Set<String>> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("firm", Set.of("SELECT", "INSERT")),
            Map.entry("farmer", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("buyer", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("village", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("item", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("expense_head", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("aadat_bill", Set.of("SELECT", "INSERT")),
            Map.entry("aadat_lot", Set.of("SELECT", "INSERT")),
            Map.entry("aadat_expense", Set.of("SELECT", "INSERT")),
            // V11: purchaser rows are read-only after save; the number counter is the only business table that is updated
            Map.entry("aadat_lot_buyer", Set.of("SELECT", "INSERT")),
            Map.entry("bill_number_counter", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("audit_event", Set.of("SELECT", "INSERT")),
            Map.entry("app_user", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("firm_member", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("credential_token", Set.of("SELECT", "INSERT", "UPDATE")),
            Map.entry("firm_invitation", Set.of("SELECT", "INSERT", "UPDATE")),
            // authentication (V8): sessions are the only place the runtime role may DELETE; the security log is append-only
            Map.entry("spring_session", Set.of("SELECT", "INSERT", "UPDATE", "DELETE")),
            Map.entry("spring_session_attributes", Set.of("SELECT", "INSERT", "UPDATE", "DELETE")),
            Map.entry("auth_throttle", Set.of("SELECT", "INSERT", "UPDATE", "DELETE")),
            Map.entry("auth_event", Set.of("SELECT", "INSERT")),
            Map.entry("flyway_schema_history", Set.of())));

    private static final List<String> ALL_TABLE_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    @Autowired DatabaseRoleGuard guard;

    /** One data source, so that the transaction manager and the JdbcTemplate share the same connection. */
    private final DataSource runtimeDs = TestcontainersConfiguration.runtimeDataSource();
    private final JdbcTemplate runtime = new JdbcTemplate(runtimeDs);
    private final JdbcTemplate owner = TestcontainersConfiguration.ownerJdbc();

    private void assertDenied(String sql) {
        // SQLSTATE 42501 = insufficient_privilege: the database refused, not some other failure.
        Throwable e = catchThrowable(() -> runtime.execute(sql));
        assertThat(e).as("expected the database to refuse: " + sql).isInstanceOf(DataAccessException.class);
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        assertThat(cause).as(sql).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) cause).getSQLState()).as(sql + " -> " + cause.getMessage()).isEqualTo("42501");
    }

    @Test
    void theRuntimeRoleIsNotPrivilegedAndOwnsNothing() {
        Map<String, Object> r = owner.queryForMap(
                "select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolreplication, rolinherit, rolcanlogin "
                        + "from pg_roles where rolname = 'mandi_app'");
        assertThat(r.get("rolsuper")).isEqualTo(false);
        assertThat(r.get("rolcreatedb")).isEqualTo(false);
        assertThat(r.get("rolcreaterole")).isEqualTo(false);
        assertThat(r.get("rolbypassrls")).isEqualTo(false);
        assertThat(r.get("rolreplication")).isEqualTo(false);
        assertThat(r.get("rolinherit")).isEqualTo(false);
        assertThat(r.get("rolcanlogin")).isEqualTo(true);
        assertThat(owner.queryForObject("select count(*) from pg_tables where tableowner = 'mandi_app'", Integer.class)).isZero();
        assertThat(owner.queryForObject("select count(*) from pg_auth_members where member = (select oid from pg_roles where rolname = 'mandi_app')",
                Integer.class)).as("no membership in any role such as pg_read_all_data").isZero();
        assertThat(owner.queryForObject("select has_schema_privilege('mandi_app', 'public', 'CREATE')", Boolean.class)).isFalse();
        assertThat(owner.queryForObject("select has_database_privilege('mandi_app', current_database(), 'CREATE')", Boolean.class)).isFalse();
    }

    @Test
    void theApplicationStartedAsTheRestrictedRoleAndTheGuardAgrees() {
        assertThat(runtime.queryForObject("select current_user", String.class)).isEqualTo("mandi_app");
        assertThat(guard.problems()).isEmpty();
    }

    @Test
    void tablePrivilegesMatchExactlyWhatTheApplicationNeeds() {
        List<String> tables = owner.queryForList("select tablename from pg_tables where schemaname = 'public'", String.class);
        assertThat(new TreeSet<>(tables)).as("every table has an explicit runtime decision").isEqualTo(EXPECTED.keySet());
        for (String table : tables) {
            Set<String> granted = new TreeSet<>();
            for (String p : ALL_TABLE_PRIVILEGES) {
                if (Boolean.TRUE.equals(owner.queryForObject("select has_table_privilege('mandi_app', ?, ?)", Boolean.class, "public." + table, p))) {
                    granted.add(p);
                }
            }
            assertThat(granted).as("privileges on " + table).isEqualTo(new TreeSet<>(EXPECTED.get(table)));
        }
    }

    @Test
    void theRuntimeRoleMayExecuteOnlyTheTwoOwnerInvitationFunctions() {
        List<String> executable = owner.queryForList("select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'public' and has_function_privilege('mandi_app', p.oid, 'EXECUTE') order by 1", String.class);
        assertThat(executable).containsExactly("accept_owner_invitation", "peek_owner_invitation");
        assertThat(owner.queryForObject("select count(*) from pg_proc p where p.proname in ('accept_owner_invitation', 'peek_owner_invitation') "
                + "and has_function_privilege('mandi_platform', p.oid, 'EXECUTE')", Integer.class)).as("never the platform role").isZero();
        assertThat(owner.queryForObject("select count(*) from pg_proc p where p.proname in ('accept_owner_invitation', 'peek_owner_invitation') "
                + "and (not p.prosecdef or p.proconfig is null)", Integer.class)).as("definer functions with a pinned search_path").isZero();
        assertDenied("select * from platform.owner_invitation");
    }

    @Test
    void theRuntimeRoleHasNothingInThePlatformSchemaAndCannotReachItBySql() {
        assertThat(owner.queryForObject("select has_schema_privilege('mandi_app', 'platform', 'USAGE')", Boolean.class)).isFalse();
        for (String t : owner.queryForList("select tablename from pg_tables where schemaname = 'platform'", String.class)) {
            for (String p : ALL_TABLE_PRIVILEGES) {
                assertThat(owner.queryForObject("select has_table_privilege('mandi_app', ?, ?)", Boolean.class, "platform." + t, p))
                        .as(p + " on platform." + t).isFalse();
            }
        }
        assertDenied("select * from platform.platform_admin");
        assertDenied("select * from platform.owner_account");
        assertDenied("insert into platform.owner_account (display_name) values ('x')");
        assertDenied("create table platform.sneaky (i int)");
        assertDenied("delete from firm_member");
        assertDenied("delete from app_user");
        assertDenied("truncate firm_member");
    }

    @Test
    void auditHistoryIsAppendOnlyForTheRuntimeRole() {
        assertDenied("update audit_event set details = 'x'");
        assertDenied("delete from audit_event");
        assertDenied("truncate audit_event");
        assertDenied("alter table audit_event disable trigger audit_event_no_change");
        assertDenied("alter table audit_event disable trigger all");
        assertDenied("drop trigger audit_event_no_change on audit_event");
        assertDenied("drop trigger audit_event_no_truncate on audit_event");
        assertDenied("drop table audit_event");
    }

    @Test
    void theRuntimeRoleCannotChangeTheSchema() {
        assertDenied("create table runtime_must_not_create (id int)");
        assertDenied("create schema runtime_must_not_create");
        assertDenied("create function runtime_must_not_create() returns int as 'select 1' language sql");
        assertDenied("alter table farmer add column runtime_must_not_add int");
        assertDenied("alter table aadat_bill drop constraint aadat_bill_farmer_firm_fk");
        assertDenied("drop table farmer");
        assertDenied("drop table aadat_bill cascade");
        assertDenied("create index runtime_must_not_create on farmer (name)");
    }

    @Test
    void theRuntimeRoleCannotSwitchOffConstraintsOrReachOtherData() {
        assertDenied("set session_replication_role = replica"); // would silently disable foreign-key checks
        assertDenied("create extension pg_trgm");
        assertDenied("select * from flyway_schema_history");
        // INSERT on firm is granted on purpose (an owner creates a firm in the application); update and delete never
        assertDenied("update firm set name = 'x'");
        assertDenied("update firm set code = 'x'");     // V11: only the print profile columns are updatable
        assertDenied("update firm set active = false");
        assertDenied("delete from aadat_lot_buyer");
        assertDenied("update aadat_lot_buyer set rate = 0");
        assertDenied("delete from bill_number_counter");
        assertDenied("delete from firm");
        assertDenied("delete from farmer");
        assertDenied("delete from aadat_bill");
        assertDenied("update aadat_bill set vehicle_no = 'x'"); // saved bills are read-only
        assertDenied("update aadat_lot set rate = 0");
        assertDenied("delete from aadat_expense");
        assertDenied("truncate farmer");
        assertDenied("alter role mandi_app superuser");
        assertDenied("create role runtime_must_not_create");
    }

    @Test
    void theRuntimeRoleMayEditOnlyTheFirmPrintProfileColumns() {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(runtimeDs);
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(tx -> {
            runtime.update("update firm set address = 'Synthetic Road', address_hi = null, phone = '000', licence_no = 'L-1', shop_no = 'S-1' where code = 'DEFAULT'");
            assertThat(runtime.queryForObject("select licence_no from firm where code = 'DEFAULT'", String.class)).isEqualTo("L-1");
            tx.setRollbackOnly();
        });
        assertThat(owner.queryForObject("select licence_no from firm where code = 'DEFAULT'", String.class)).isNull();
    }

    @Test
    void whatTheApplicationNeedsStillWorks_includingIdentityColumnsAndRollback() {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(runtimeDs);
        TransactionTemplate tx = new TransactionTemplate(tm);
        Long firmId = runtime.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        int before = owner.queryForObject("select count(*) from audit_event", Integer.class);
        tx.executeWithoutResult(status -> {
            // INSERT works and the identity column generates ids although the role has no sequence privilege
            Long farmerId = runtime.queryForObject("insert into farmer (name, firm_id) values ('Privilege Probe', ?) returning id", Long.class, firmId);
            assertThat(farmerId).isNotNull();
            assertThat(runtime.update("update farmer set name = 'Privilege Probe 2', active = false where id = ?", farmerId)).isEqualTo(1);
            runtime.update("insert into audit_event (entity_type, entity_id, action, details, firm_id) values ('RUNTIME_TEST', ?, 'CREATED', 'probe', ?)",
                    farmerId, firmId);
            assertThat(runtime.queryForObject("select count(*) from audit_event where entity_type = 'RUNTIME_TEST'", Integer.class)).isEqualTo(1);
            status.setRollbackOnly(); // leave the shared database exactly as it was
        });
        assertThat(owner.queryForObject("select count(*) from farmer where name like 'Privilege Probe%'", Integer.class)).isZero();
        assertThat(owner.queryForObject("select count(*) from audit_event", Integer.class)).isEqualTo(before);
    }

    @Test
    void theDatabaseStillRejectsCrossFirmReferencesForTheRuntimeRole() {
        Long firmId = runtime.queryForObject("select id from firm where code = 'DEFAULT'", Long.class);
        Long other = owner.queryForObject("insert into firm (code, name) values ('PRIV_OTHER_' || floor(random()*1000000)::int, 'other') returning id", Long.class);
        Long farmer = owner.queryForObject("insert into farmer (name, firm_id) values ('Other firm farmer', ?) returning id", Long.class, other);
        try {
            assertThatThrownBy(() -> runtime.update(
                    "insert into aadat_bill (bill_date, farmer_id, calculation_status, rule_set_id, firm_id) values (current_date, ?, 'NOT_CALCULATED', 'x', ?)",
                    farmer, firmId))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("foreign key");
        } finally {
            owner.update("delete from farmer where id = ?", farmer);
            owner.update("delete from firm where id = ?", other);
        }
    }

    @Test
    void temporaryTablesAreNotInheritedFromPublicAndTheConnectionLimitIsSet() {
        assertDenied("create temporary table runtime_must_not_create (id int)");
        assertThat(owner.queryForObject("select has_database_privilege('mandi_app', current_database(), 'TEMPORARY')", Boolean.class)).isFalse();
        assertThat(owner.queryForObject("select rolconnlimit from pg_roles where rolname = 'mandi_app'", Integer.class))
                .as("documented connection budget (docs/DB_BACKUP_AND_ROLES.md)").isEqualTo(30);
    }

    /** New tables and sequences must start with NO runtime access, whatever migration creates them. */
    @Test
    void newTablesAndSequencesAreFailClosed() {
        // V7 deliberately adds ONE default ACL that only REMOVES access (no EXECUTE on new functions for PUBLIC). The check is
        // that no default ACL GRANTS anything to anybody but the owner.
        assertThat(owner.queryForObject("select count(*) from pg_default_acl a, aclexplode(a.defaclacl) x where x.grantee <> a.defaclrole", Integer.class))
                .as("no default privileges that would silently open future tables").isZero();
        assertThat(owner.queryForObject("select count(*) from pg_default_acl where defaclobjtype in ('r', 'S', 'n', 'T')", Integer.class))
                .as("no default ACL at all for tables, sequences, schemas or types").isZero();
        for (String seq : owner.queryForList("select relname from pg_class where relkind = 'S' and relnamespace = 'public'::regnamespace", String.class)) {
            for (String p : List.of("USAGE", "SELECT", "UPDATE")) {
                assertThat(owner.queryForObject("select has_sequence_privilege('mandi_app', ?, ?)", Boolean.class, "public." + seq, p))
                        .as(p + " on sequence " + seq).isFalse();
            }
        }
        owner.execute("create table zz_fail_closed_probe (id bigint generated always as identity primary key, v text)");
        try {
            for (String p : ALL_TABLE_PRIVILEGES) {
                assertThat(owner.queryForObject("select has_table_privilege('mandi_app', 'public.zz_fail_closed_probe', ?)", Boolean.class, p))
                        .as(p + " on a table created after the grants ran").isFalse();
            }
            assertDenied("select * from zz_fail_closed_probe");
            assertDenied("insert into zz_fail_closed_probe (v) values ('x')");
            for (String seq : owner.queryForList("select relname from pg_class where relkind = 'S' and relname like 'zz_fail_closed_probe%'", String.class)) {
                assertThat(owner.queryForObject("select has_sequence_privilege('mandi_app', ?, 'USAGE')", Boolean.class, "public." + seq)).isFalse();
            }
        } finally {
            owner.execute("drop table zz_fail_closed_probe");
        }
    }
}
