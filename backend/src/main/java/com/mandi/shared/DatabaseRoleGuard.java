package com.mandi.shared;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Checks, once at start-up, that the runtime database connection is NOT a privileged one. A superuser (or an
 * owner of the tables) can switch off the append-only audit triggers and bypass every constraint, so it must not
 * be what the web application runs as (docs/DB_BACKUP_AND_ROLES.md).
 *
 * <p>Fails closed: unless {@code mandi.db.require-restricted-role} is explicitly {@code false} a privileged connection
 * refuses to start. Only the {@code dev} profile (application-dev.properties) sets it to false, where a privileged
 * connection just logs a warning so the local demo keeps working. {@link StartupSafetyEnvironmentPostProcessor}
 * rejects {@code false} in every other profile.
 */
@Component
public class DatabaseRoleGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleGuard.class);

    private final JdbcTemplate jdbc;
    private final boolean require;

    public DatabaseRoleGuard(DataSource dataSource,
                             @Value("${mandi.db.require-restricted-role:true}") boolean require) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.require = require;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            log.info("Database runtime role is restricted (no superuser, no bypass of row security, owns no tables).");
            return;
        }
        String message = "The application's database role is privileged: " + String.join("; ", problems)
                + ". Run the application as the restricted runtime role (docs/DB_BACKUP_AND_ROLES.md).";
        if (require) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    public List<String> problems() {
        Map<String, Object> role = jdbc.queryForMap(
                "select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls from pg_roles where rolname = current_user");
        Integer owned = jdbc.queryForObject(
                "select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user", Integer.class);
        List<String> out = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(role.get("rolsuper"))) out.add("it is a superuser");
        if (Boolean.TRUE.equals(role.get("rolbypassrls"))) out.add("it bypasses row-level security");
        if (Boolean.TRUE.equals(role.get("rolcreatedb"))) out.add("it can create databases");
        if (Boolean.TRUE.equals(role.get("rolcreaterole"))) out.add("it can create roles");
        if (owned != null && owned > 0) out.add("it owns " + owned + " application tables");
        return out;
    }
}
