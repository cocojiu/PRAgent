package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MigrationDemoDataGuardTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path DEMO_DIR = Path.of("src/main/resources/db/demo");
    private static final int DEMO_PURGE_VERSION = 36;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    void demoSeedScriptsStayOutsideFutureFlywayMigrations() throws IOException {
        List<Path> futureDemoMigrations = migrationFiles().stream()
            .filter(path -> migrationVersion(path) > DEMO_PURGE_VERSION)
            .filter(this::containsDemoSeedMarker)
            .toList();

        assertThat(futureDemoMigrations)
            .as("Demo seed data belongs in src/main/resources/db/demo, not future Flyway migrations")
            .isEmpty();
    }

    @Test
    void demoDirectoryDocumentsManualLoadingContract() throws IOException {
        String demoReadme = Files.readString(DEMO_DIR.resolve("DEMO_DATA.txt"), StandardCharsets.UTF_8);

        assertThat(DEMO_DIR.resolve("seed_review_demo_data.sql")).exists();
        assertThat(DEMO_DIR.resolve("seed_llm_quality_demo_data.sql")).exists();
        assertThat(demoReadme)
            .contains("kept outside db/migration")
            .contains("Do not add new demo seed scripts under src/main/resources/db/migration");
    }

    private List<Path> migrationFiles() throws IOException {
        try (var files = Files.list(MIGRATION_DIR)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private int migrationVersion(Path path) {
        Matcher matcher = VERSION_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private boolean containsDemoSeedMarker(Path path) {
        try {
            String sql = Files.readString(path, StandardCharsets.UTF_8).toLowerCase();
            return sql.contains("repo-guard-demo")
                || sql.contains("seed_demo")
                || sql.contains("seed_llm_quality_demo_data")
                || sql.contains("demo901a")
                || sql.contains("demo902b")
                || sql.contains("demo903c")
                || sql.contains("demo904d");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read migration " + path, ex);
        }
    }
}
