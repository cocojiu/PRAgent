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
    private static final List<String> HISTORICAL_DEMO_SEED_MIGRATIONS = List.of(
        "V2__seed_demo_data.sql",
        "V24__seed_llm_quality_demo_data.sql"
    );
    private static final List<String> FINAL_PURGE_REQUIRED_TABLES = List.of(
        "notification_delivery_log",
        "notification_event",
        "github_comment_publication_batch_item",
        "github_comment_publication_batch",
        "github_comment_publication",
        "review_timeline",
        "review_finding",
        "changed_file",
        "review_task"
    );
    private static final List<String> FINAL_PURGE_REQUIRED_MARKERS = List.of(
        "repo-guard-demo",
        "monorepo",
        "https://github.com/repo-guard-demo/%",
        "https://github.com/monorepo/%",
        "a1b2c3d",
        "d4e5f6q",
        "h7i8j9k",
        "11m2n3o",
        "p4q5r6s",
        "t7u8v9w",
        "x1y2z3a",
        "b4c5d6e",
        "demo901a",
        "demo902b",
        "demo903c",
        "demo904d"
    );
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

    @Test
    void finalDemoPurgeCoversHistoricalSeedMarkers() throws IOException {
        String purgeSql = migrationSql("V36__purge_demo_review_data.sql");

        for (String marker : FINAL_PURGE_REQUIRED_MARKERS) {
            assertThat(purgeSql)
                .as("Final demo purge migration must cover historical demo marker: %s", marker)
                .contains(marker);
        }
    }

    @Test
    void finalDemoPurgeDeletesAllKnownDemoDependentTables() throws IOException {
        String purgeSql = migrationSql("V36__purge_demo_review_data.sql").toLowerCase();

        assertThat(purgeSql)
            .as("Final demo purge should collect task ids through a bounded demo marker query")
            .contains("demo_review_task_ids")
            .contains("organization in ('repo-guard-demo', 'monorepo')")
            .doesNotContain("where id between 505 and 512");
        for (String table : FINAL_PURGE_REQUIRED_TABLES) {
            assertThat(purgeSql)
                .as("Final demo purge must delete demo-owned rows from %s", table)
                .contains(table);
        }
    }

    @Test
    void historicalDemoSeedMigrationsRemainNeutralizedByCleanupMigrations() throws IOException {
        for (String migration : HISTORICAL_DEMO_SEED_MIGRATIONS) {
            assertThat(containsDemoSeedMarker(MIGRATION_DIR.resolve(migration)))
                .as("Historical demo seed migration must remain visible to the guard: %s", migration)
                .isTrue();
        }
        assertThat(migrationSql("V25__remove_legacy_v2_demo_data.sql"))
            .contains("delete from review_task where id between 505 and 512");
        assertThat(migrationSql("V35__remove_llm_quality_demo_data.sql"))
            .contains("task.id in (9001, 9002, 9003, 9004)")
            .contains("task.organization = 'repo-guard-demo'")
            .contains("task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d')");
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

    private String migrationSql(String fileName) throws IOException {
        return Files.readString(MIGRATION_DIR.resolve(fileName), StandardCharsets.UTF_8);
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
