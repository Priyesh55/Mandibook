package com.mandi.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Refuses to start when the effective configuration is unsafe, BEFORE any database connection or migration is made.
 *
 * <p>Fail-closed rule: unless the explicit development profile {@code dev} is active (the {@code demo} profile implies
 * it), the application is treated as production-like. Then every database credential must be configured explicitly,
 * the runtime role must not be the privileged owner or a documented development password, Flyway must use a different
 * (migration-owner) role, the restricted-role guard must be on, and no synthetic seed or demonstration calculation may
 * be enabled. The {@code test} profile (set by Surefire) only keeps the restricted-role guard and network rules: the
 * Testcontainers configuration injects the database credentials after this check runs (the tests then run as the real
 * restricted role and the guard verifies it at runtime), and the tests that exercise the demonstration calculation
 * need it enabled.
 *
 * <p>Independent of the profile, a listen address other than loopback needs the explicit second switch
 * {@code mandi.network.allow-non-loopback=true}: there is no authentication yet, so exposing the API is a deliberate act.
 *
 * <p>Runs after the configuration files are loaded (lowest precedence), so profile groups are already resolved.
 */
public class StartupSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEV = "dev";
    static final String TEST = "test";
    /** Role names that own the schema or administer the server; never acceptable as the runtime role. */
    static final Set<String> PRIVILEGED_ROLE_NAMES = Set.of("mandi", "postgres");
    /** Passwords printed in repository files; never acceptable outside the dev profile. */
    static final Set<String> DOCUMENTED_DEV_PASSWORDS = Set.of("mandi_dev_only");
    static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> problems = violations(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: unsafe configuration.\n  - "
                    + String.join("\n  - ", problems)
                    + "\nSee docs/RUNNING.md ('Configuration profiles') and docs/DB_BACKUP_AND_ROLES.md. "
                    + "For local development use the explicit profile 'dev' or 'demo'.");
        }
    }

    /** Every reason the effective configuration is unsafe; empty when it is acceptable. */
    public static List<String> violations(Environment env) {
        List<String> out = new ArrayList<>();
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        boolean dev = profiles.contains(DEV);
        boolean test = profiles.contains(TEST);

        // Network: applies to every profile.
        String address = value(env, "server.address");
        boolean allowNonLoopback = "true".equalsIgnoreCase(value(env, "mandi.network.allow-non-loopback"));
        if (!isLoopback(address) && !allowNonLoopback) {
            out.add("server.address is '" + (address == null ? "(not set: all interfaces)" : address)
                    + "', not loopback. There is no authentication yet; a deliberate non-local deployment needs "
                    + "SERVER_ADDRESS and MANDI_ALLOW_NON_LOOPBACK=true.");
        }

        if (dev) {
            return out;
        }

        if (!test) {
            checkCredentialsAndDevelopmentFeatures(env, out);
        }
        if (!"true".equalsIgnoreCase(value(env, "mandi.db.require-restricted-role"))) {
            out.add("mandi.db.require-restricted-role must be true (the start-up check that the runtime role is not privileged).");
        }
        if (profiles.contains("demo")) {
            // 'demo' implies 'dev' through the profile group; reaching here means the group was overridden.
            out.add("Profile 'demo' is active without 'dev'.");
        }
        return out;
    }

    /** Session cookie, origins and the keyed-hash secret: production-like profiles only (dev and test run over plain HTTP). */
    private static void checkAuthentication(Environment env, List<String> out) {
        String key = value(env, "mandi.security.hmac-key");
        if (blank(key)) out.add("MANDI_SECURITY_HMAC_KEY is not set (key for the keyed hashes in the security log and login throttle).");
        else if (key.length() < 32) out.add("mandi.security.hmac-key is shorter than 32 characters.");
        else if (key.startsWith("development-only")) out.add("mandi.security.hmac-key is the development key.");
        String origins = value(env, "mandi.security.allowed-origins");
        if (blank(origins)) out.add("MANDI_ALLOWED_ORIGINS is not set (the public origin(s) of the web application, for the cross-site request check).");
        else if (origins.contains("*")) out.add("mandi.security.allowed-origins contains a wildcard.");
        String name = value(env, "server.servlet.session.cookie.name");
        if (name == null || !name.startsWith("__Host-")) out.add("The session cookie name must start with __Host- (it then cannot be set by a sibling site).");
        if (!"true".equalsIgnoreCase(value(env, "server.servlet.session.cookie.secure"))) out.add("The session cookie must be Secure.");
        String httpOnly = value(env, "server.servlet.session.cookie.http-only");
        if (httpOnly != null && !"true".equalsIgnoreCase(httpOnly)) out.add("The session cookie must be HttpOnly.");
        String sameSite = value(env, "server.servlet.session.cookie.same-site");
        if (sameSite == null || !(sameSite.equalsIgnoreCase("lax") || sameSite.equalsIgnoreCase("strict"))) out.add("The session cookie SameSite must be lax or strict.");
        String path = value(env, "server.servlet.session.cookie.path");
        if (path != null && !path.equals("/")) out.add("The session cookie path must be / (required by the __Host- prefix).");
        if (!blank(value(env, "server.servlet.session.cookie.domain"))) out.add("The session cookie must not set a Domain (required by the __Host- prefix).");
    }

    private static void checkCredentialsAndDevelopmentFeatures(Environment env, List<String> out) {
        checkAuthentication(env, out);
        if ("true".equalsIgnoreCase(value(env, "mandi.mail.dev-sink"))) {
            out.add("mandi.mail.dev-sink is true (it exposes raw invitation links; development and test only).");
        }
        String url = value(env, "spring.datasource.url");
        String user = value(env, "spring.datasource.username");
        String password = value(env, "spring.datasource.password");
        String flywayUrl = value(env, "spring.flyway.url");
        String flywayUser = value(env, "spring.flyway.user");
        String flywayPassword = value(env, "spring.flyway.password");

        if (blank(url)) out.add("DB_URL is not set (runtime database URL).");
        if (blank(user)) out.add("DB_USER is not set (runtime role, normally mandi_app).");
        if (blank(password)) out.add("DB_PASSWORD is not set (runtime role password).");
        if (blank(flywayUrl)) out.add("MIGRATION_DB_URL / DB_URL is not set (Flyway URL).");
        if (blank(flywayUser)) out.add("MIGRATION_DB_USER is not set (Flyway uses a separate migration-owner role).");
        if (blank(flywayPassword)) out.add("MIGRATION_DB_PASSWORD is not set (migration-owner password).");

        if (!blank(user) && PRIVILEGED_ROLE_NAMES.contains(user.toLowerCase())) {
            out.add("The runtime role '" + user + "' is the privileged owner/admin role. Run as the restricted role mandi_app.");
        }
        if (!blank(user) && !blank(flywayUser) && user.equals(flywayUser)) {
            out.add("The runtime role and the Flyway role are the same ('" + user + "'). They must be different roles.");
        }
        if (!blank(password) && DOCUMENTED_DEV_PASSWORDS.contains(password)) {
            out.add("The runtime password is a documented development password.");
        }
        if (!blank(flywayPassword) && DOCUMENTED_DEV_PASSWORDS.contains(flywayPassword)) {
            out.add("The migration password is a documented development password.");
        }

        if ("true".equalsIgnoreCase(value(env, "mandi.calculation.demo-enabled"))) {
            out.add("mandi.calculation.demo-enabled is true (demonstration calculation is development-only).");
        }
        String locations = value(env, "spring.flyway.locations");
        if (locations != null && locations.contains("db/seed")) {
            out.add("spring.flyway.locations includes db/seed (synthetic demo data is development-only).");
        }
        if ("true".equalsIgnoreCase(value(env, "spring.flyway.out-of-order"))) {
            out.add("spring.flyway.out-of-order is true (development-only).");
        }
    }

    private static boolean isLoopback(String address) {
        return address != null && LOOPBACK.contains(address.trim().toLowerCase());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** The resolved property, or null when it is missing or still contains an unresolvable placeholder. */
    private static String value(Environment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }
}
