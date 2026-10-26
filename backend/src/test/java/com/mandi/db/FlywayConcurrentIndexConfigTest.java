package com.mandi.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Fast configuration guard (no Spring context, no database). If one of these settings is removed, the real failure
 * is not an error but a hang: Flyway's migration blocks forever on CREATE INDEX CONCURRENTLY. A hang would only be
 * noticed after a build timeout, so the settings are checked here, in milliseconds, with a clear message.
 */
class FlywayConcurrentIndexConfigTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void flywayHoldsItsMigrationLockWithoutAnOpenTransaction() throws IOException {
        Properties p = new Properties();
        try (InputStream in = FlywayConcurrentIndexConfigTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).as("application.properties is on the classpath").isNotNull();
            p.load(in);
        }
        assertThat(p.getProperty("spring.flyway.postgresql.transactional-lock"))
                .as("without this, Flyway keeps a transaction open and CREATE INDEX CONCURRENTLY (V5) waits for it forever")
                .isEqualTo("false");
    }

    @Test
    void everyMigrationThatBuildsIndexesConcurrentlyRunsOutsideATransaction() throws IOException {
        List<Path> concurrent;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            concurrent = files.filter(f -> f.getFileName().toString().matches("[VR].*\\.sql")).filter(f -> {
                try {
                    return Files.readString(f).toLowerCase().matches("(?s).*create\\s+(unique\\s+)?index\\s+concurrently.*");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(concurrent).as("V5 builds its indexes concurrently").isNotEmpty();
        for (Path sql : concurrent) {
            Path conf = sql.resolveSibling(sql.getFileName() + ".conf");
            assertThat(conf).as(sql.getFileName() + " needs a sibling .conf with executeInTransaction=false").exists();
            assertThat(Files.readAllLines(conf).stream().map(String::trim).toList())
                    .as(conf.getFileName().toString()).contains("executeInTransaction=false");
        }
    }

    @Test
    void aMigrationThatBuildsConcurrentIndexesChecksForInvalidOnesAtTheEnd() throws IOException {
        String v5 = Files.readString(MIGRATIONS.resolve("V5__additive_indexes.sql")).toLowerCase();
        assertThat(v5).as("a failed concurrent build leaves an INVALID index that IF NOT EXISTS would accept")
                .contains("indisvalid").contains("raise exception");
    }
}
