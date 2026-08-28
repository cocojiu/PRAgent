package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewExecutionAttemptMigrationTest {

    @Test
    void attemptMigrationAddsGenerationAppendOnlyOwnershipAndCurrentReadIndexes() throws IOException {
        String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V65__review_execution_attempts_and_pull_request_generation.sql"
        )).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("create table if not exists review_pull_request_head")
            .contains("create table if not exists review_execution_attempt")
            .contains("unique key uk_review_execution_attempt_no (task_id, attempt_no)")
            .contains("add column current_attempt_id")
            .contains("add column attempt_id")
            .contains("add column current_attempt")
            .contains("idx_changed_file_attempt")
            .contains("idx_review_finding_attempt_id")
            .contains("idx_review_finding_current_category_file")
            .contains("foreign key (attempt_id) references review_execution_attempt(id)");
    }

    @Test
    void indexRetirementIsReversibleBeforePhysicalDrop() throws IOException {
        String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V66__retire_legacy_dashboard_indexes.sql"
        )).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("idx_review_task_dashboard_created_risk invisible")
            .contains("idx_review_task_dashboard_created_llm_model invisible")
            .contains("idx_review_task_dashboard_created_llm_repo invisible")
            .doesNotContain("drop index");
    }
}
