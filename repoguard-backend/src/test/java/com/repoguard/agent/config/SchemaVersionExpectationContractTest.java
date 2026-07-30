package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code repoguard.schema.expected-version} aligned with the migration
 * chain. Without this, adding a migration would silently leave non-owner roles
 * accepting a schema one version behind.
 */
class SchemaVersionExpectationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");
    private static final Pattern EXPECTED_VERSION =
        Pattern.compile("expected-version:\\s*\\$\\{REPOGUARD_SCHEMA_EXPECTED_VERSION:(\\d+)}");

    @Test
    void expectedVersionMatchesHighestMigration() throws IOException {
        assertThat(configuredExpectedVersion())
            .as("repoguard.schema.expected-version must match the highest Flyway migration")
            .isEqualTo(highestMigrationVersion());
    }

    @Test
    void guardDefaultMatchesConfiguredExpectation() throws IOException {
        assertThat(new SchemaVersionProperties().getExpectedVersion())
            .as("SchemaVersionProperties default must match application.yml so tests and runtime agree")
            .isEqualTo(configuredExpectedVersion());
    }

    private int highestMigrationVersion() throws IOException {
        try (Stream<Path> migrations = Files.list(MIGRATION_DIR)) {
            return migrations
                .map(path -> path.getFileName().toString())
                .map(MIGRATION_VERSION::matcher)
                .filter(Matcher::find)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElseThrow(() -> new IllegalStateException("No Flyway migrations found under " + MIGRATION_DIR));
        }
    }

    private int configuredExpectedVersion() throws IOException {
        String applicationYml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
        Matcher matcher = EXPECTED_VERSION.matcher(applicationYml);
        assertThat(matcher.find())
            .as("application.yml must declare repoguard.schema.expected-version "
                + "as ${REPOGUARD_SCHEMA_EXPECTED_VERSION:<n>}")
            .isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
