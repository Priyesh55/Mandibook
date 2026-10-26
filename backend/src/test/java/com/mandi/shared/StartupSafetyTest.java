package com.mandi.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Unsafe production configuration must fail closed, before any database is touched, and the development convenience
 * must stay explicit. The first group tests the rules on a bare environment; the second starts a real (empty)
 * SpringApplication so the actual application.properties / application-dev.properties and the spring.factories
 * registration are exercised. No database is used.
 */
class StartupSafetyTest {

    // ---- rules on a bare environment ----------------------------------------------------------------------

    private static Map<String, Object> safe() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("server.address", "127.0.0.1");
        m.put("spring.datasource.url", "jdbc:postgresql://db.invalid/mandi");
        m.put("spring.datasource.username", "mandi_app");
        m.put("spring.datasource.password", "p-runtime-9f3a1c");
        m.put("spring.flyway.url", "jdbc:postgresql://db.invalid/mandi");
        m.put("spring.flyway.user", "mandi_migrator");
        m.put("spring.flyway.password", "p-migrator-77b2e0");
        m.put("mandi.db.require-restricted-role", "true");
        m.put("mandi.security.hmac-key", "k".repeat(40));
        m.put("mandi.security.allowed-origins", "https://app.example.test");
        m.put("server.servlet.session.cookie.name", "__Host-mandi_session");
        m.put("server.servlet.session.cookie.secure", "true");
        m.put("server.servlet.session.cookie.same-site", "lax");
        return m;
    }

    @Test
    void sessionCookieAndAuthenticationSecretsAreCheckedOutsideDevAndTest() {
        Map<String, Object> p = safe();
        p.put("server.servlet.session.cookie.secure", "false");
        p.put("server.servlet.session.cookie.name", "mandi_session");
        p.put("server.servlet.session.cookie.http-only", "false");
        p.put("server.servlet.session.cookie.same-site", "none");
        p.put("server.servlet.session.cookie.domain", "example.test");
        p.put("server.servlet.session.cookie.path", "/api");
        p.put("mandi.security.hmac-key", "short");
        p.put("mandi.security.allowed-origins", "*");
        List<String> v = check(p, "prod");
        assertThat(v).anyMatch(s -> s.contains("must start with __Host-")).anyMatch(s -> s.contains("must be Secure"))
                .anyMatch(s -> s.contains("must be HttpOnly")).anyMatch(s -> s.contains("SameSite"))
                .anyMatch(s -> s.contains("Domain")).anyMatch(s -> s.contains("path must be /"))
                .anyMatch(s -> s.contains("shorter than 32")).anyMatch(s -> s.contains("wildcard"));
        Map<String, Object> missing = safe();
        missing.remove("mandi.security.hmac-key");
        missing.put("mandi.security.allowed-origins", "${MANDI_ALLOWED_ORIGINS}");   // unresolvable, as in the baseline file
        assertThat(check(missing, "prod")).anyMatch(s -> s.contains("MANDI_SECURITY_HMAC_KEY")).anyMatch(s -> s.contains("MANDI_ALLOWED_ORIGINS"));
        Map<String, Object> devKey = safe();
        devKey.put("mandi.security.hmac-key", "development-only-hmac-key-0123456789-abcdef");
        assertThat(check(devKey, "prod")).anyMatch(s -> s.contains("development key"));
        Map<String, Object> sink = safe();
        sink.put("mandi.mail.dev-sink", "true");
        assertThat(check(sink, "prod")).anyMatch(s -> s.contains("dev-sink"));
        assertThat(check(sink, "dev")).isEmpty();
        assertThat(check(sink, "test")).isEmpty();
        // plain HTTP is allowed in dev and test only
        assertThat(check(p, "test")).isEmpty();
        assertThat(check(p, "dev")).isEmpty();
    }

    private static List<String> check(Map<String, Object> props, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", props));
        env.setActiveProfiles(profiles);
        return StartupSafetyEnvironmentPostProcessor.violations(env);
    }

    @Test
    void aSafeProductionLikeConfigurationHasNoViolations() {
        assertThat(check(safe(), "prod")).isEmpty();
        assertThat(check(safe())).as("no profile at all is treated as production-like").isEmpty();
    }

    @Test
    void missingCredentialsAreReportedByName() {
        Map<String, Object> p = safe();
        p.put("spring.datasource.username", "${DB_USER}");      // unresolvable placeholder, as in the baseline file
        p.put("spring.datasource.password", "");
        p.put("spring.flyway.user", "${MIGRATION_DB_USER}");
        p.put("spring.flyway.password", "${MIGRATION_DB_PASSWORD}");
        assertThat(check(p, "prod")).anyMatch(s -> s.contains("DB_USER"))
                .anyMatch(s -> s.contains("DB_PASSWORD"))
                .anyMatch(s -> s.contains("MIGRATION_DB_USER"))
                .anyMatch(s -> s.contains("MIGRATION_DB_PASSWORD"));
    }

    @Test
    void ownerRoleAndDocumentedPasswordAreRefusedOutsideDev() {
        Map<String, Object> p = safe();
        p.put("spring.datasource.username", "mandi");
        p.put("spring.datasource.password", "mandi_dev_only");
        p.put("spring.flyway.password", "mandi_dev_only");
        List<String> v = check(p, "prod");
        assertThat(v).anyMatch(s -> s.contains("privileged owner/admin role"))
                .anyMatch(s -> s.contains("runtime password is a documented development password"))
                .anyMatch(s -> s.contains("migration password is a documented development password"));
        assertThat(check(Map.of("spring.datasource.username", "postgres"), "prod"))
                .anyMatch(s -> s.contains("privileged owner/admin role"));
    }

    @Test
    void runtimeAndMigrationRolesMustDiffer() {
        Map<String, Object> p = safe();
        p.put("spring.flyway.user", "mandi_app");
        assertThat(check(p, "prod")).anyMatch(s -> s.contains("must be different roles"));
    }

    @Test
    void theRestrictedRoleGuardCannotBeSwitchedOffOutsideDev() {
        Map<String, Object> p = safe();
        p.put("mandi.db.require-restricted-role", "false");
        assertThat(check(p, "prod")).anyMatch(s -> s.contains("require-restricted-role must be true"));
        p.remove("mandi.db.require-restricted-role");
        assertThat(check(p, "prod")).anyMatch(s -> s.contains("require-restricted-role must be true"));
    }

    @Test
    void demoCalculationSeedAndOutOfOrderAreDevelopmentOnly() {
        Map<String, Object> p = safe();
        p.put("mandi.calculation.demo-enabled", "true");
        p.put("spring.flyway.locations", "classpath:db/migration,classpath:db/seed");
        p.put("spring.flyway.out-of-order", "true");
        List<String> v = check(p, "prod");
        assertThat(v).anyMatch(s -> s.contains("demo-enabled")).anyMatch(s -> s.contains("db/seed"))
                .anyMatch(s -> s.contains("out-of-order"));
        // the automated tests (profile test) may exercise them; the test infrastructure injects the credentials later
        // (checked at runtime by DatabaseRoleGuard), but the guard switch and the network rule still apply
        assertThat(check(p, "test")).isEmpty();
        Map<String, Object> guardOff = safe();
        guardOff.put("mandi.db.require-restricted-role", "false");
        assertThat(check(guardOff, "test")).anyMatch(s -> s.contains("require-restricted-role must be true"));
        Map<String, Object> exposed = safe();
        exposed.put("server.address", "0.0.0.0");
        assertThat(check(exposed, "test")).anyMatch(s -> s.contains("not loopback"));
    }

    @Test
    void devProfileRelaxesCredentialRulesButNotTheNetworkRule() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("server.address", "127.0.0.1");
        p.put("spring.datasource.username", "mandi");
        p.put("spring.datasource.password", "mandi_dev_only");
        p.put("mandi.db.require-restricted-role", "false");
        p.put("mandi.calculation.demo-enabled", "true");
        assertThat(check(p, "dev")).isEmpty();
        p.put("server.address", "0.0.0.0");
        assertThat(check(p, "dev")).anyMatch(s -> s.contains("not loopback"));
    }

    @Test
    void nonLoopbackAddressNeedsTheSecondExplicitSwitch() {
        for (String exposed : new String[] {"0.0.0.0", "::", "192.168.1.20", "", "myhost"}) {
            Map<String, Object> p = safe();
            p.put("server.address", exposed);
            assertThat(check(p, "prod")).as("address '%s'", exposed).anyMatch(s -> s.contains("not loopback"));
            p.put("mandi.network.allow-non-loopback", "true");
            assertThat(check(p, "prod")).as("address '%s' with the switch", exposed).isEmpty();
        }
        for (String local : new String[] {"127.0.0.1", "localhost", "::1", "[::1]"}) {
            Map<String, Object> p = safe();
            p.put("server.address", local);
            assertThat(check(p, "prod")).as(local).isEmpty();
        }
        Map<String, Object> unset = safe();
        unset.remove("server.address");
        assertThat(check(unset, "prod")).as("no address means all interfaces").anyMatch(s -> s.contains("not loopback"));
    }

    // ---- real application start (empty context, real property files, no database) ---------------------------

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    private static ConfigurableApplicationContext start(String... args) {
        List<String> all = new ArrayList<>(List.of("--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        all.addAll(List.of(args));
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app.run(all.toArray(String[]::new));
    }

    @Test
    void productionStartWithNothingConfiguredFailsClearlyBeforeAnyDatabaseUse() {
        assertThatThrownBy(() -> start("--spring.profiles.active=prod",
                "--DB_URL=", "--DB_USER=", "--DB_PASSWORD=", "--MIGRATION_DB_URL=", "--MIGRATION_DB_USER=",
                "--MIGRATION_DB_PASSWORD="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("DB_USER")
                .hasMessageContaining("MIGRATION_DB_USER");
    }

    @Test
    void productionStartWithTheOwnerRoleAndTheDevPasswordFails() {
        assertThatThrownBy(() -> start("--spring.profiles.active=prod",
                "--DB_URL=jdbc:postgresql://127.0.0.1:1/x", "--DB_USER=mandi", "--DB_PASSWORD=mandi_dev_only",
                "--MIGRATION_DB_USER=mandi", "--MIGRATION_DB_PASSWORD=mandi_dev_only"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("privileged owner/admin role")
                .hasMessageContaining("documented development password")
                .hasMessageContaining("must be different roles");
    }

    @Test
    void theBaselineFileKeepsTheGuardOnAndLoopbackBound() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=prod",
                "--DB_URL=jdbc:postgresql://127.0.0.1:1/x", "--DB_USER=mandi_app", "--DB_PASSWORD=p-runtime-9f3a1c",
                "--MIGRATION_DB_URL=jdbc:postgresql://127.0.0.1:1/x", "--MIGRATION_DB_USER=mandi_migrator",
                "--MIGRATION_DB_PASSWORD=p-migrator-77b2e0", "--MANDI_SECURITY_HMAC_KEY=" + "k".repeat(40),
                "--MANDI_ALLOWED_ORIGINS=https://app.example.test")) {
            assertThat(ctx.getEnvironment().getProperty("mandi.db.require-restricted-role")).isEqualTo("true");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.name")).isEqualTo("__Host-mandi_session");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
            assertThat(ctx.getEnvironment().getProperty("mandi.security.session.absolute-lifetime")).isEqualTo("PT18H");
            assertThat(ctx.getEnvironment().getProperty("mandi.security.session.idle-timeout")).isEqualTo("PT30M");
            assertThat(ctx.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(ctx.getEnvironment().getProperty("mandi.calculation.demo-enabled")).isEqualTo("false");
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.locations"))
                    .as("production never loads the synthetic seed").isNull();
        }
    }

    @Test
    void demoProfileImpliesDevAndIsTheOnlyWayToTheDevDefaults() {
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=demo")) {
            assertThat(ctx.getEnvironment().getActiveProfiles()).contains("demo", "dev");
            assertThat(ctx.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.locations")).contains("db/seed");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.name")).as("development over plain http").isEqualTo("mandi_session");
            assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
            assertThat(ctx.getEnvironment().getProperty("mandi.security.allowed-origins")).contains("http://localhost:3000");
        }
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=dev")) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).isNotBlank();
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.locations")).isNull();
        }
    }

    @Test
    void noProfileAtAllIsTreatedAsProductionAndRefusesToStart() {
        assertThatThrownBy(() -> start("--spring.profiles.active=", "--DB_URL=", "--DB_USER=", "--DB_PASSWORD=",
                "--MIGRATION_DB_URL=", "--MIGRATION_DB_USER=", "--MIGRATION_DB_PASSWORD="))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start");
    }

    @Test
    void aNonLoopbackBindWithoutTheSecondSwitchRefusesToStartEvenInDev() {
        assertThatThrownBy(() -> start("--spring.profiles.active=dev", "--SERVER_ADDRESS=0.0.0.0"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not loopback");
        try (ConfigurableApplicationContext ctx = start("--spring.profiles.active=dev", "--SERVER_ADDRESS=0.0.0.0",
                "--MANDI_ALLOW_NON_LOOPBACK=true")) {
            assertThat(ctx.getEnvironment().getProperty("server.address")).isEqualTo("0.0.0.0");
        }
    }
}
