package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChangedFileFindingLookupIndexMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V43__changed_file_finding_lookup_indexes.sql"
    );

    @Test
    void migrationAddsCoveringIndexesForChangedFileFindingLookup() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
            .contains("idx_review_finding_task_category_file")
            .contains("review_finding add key idx_review_finding_task_category_file (task_id, category, file_path(255))")
            .contains("idx_changed_file_task_file")
            .contains("changed_file add key idx_changed_file_task_file (task_id, file_path(255))")
            .doesNotContain("select distinct file_path");
    }
}
