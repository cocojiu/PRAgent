package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DashboardNormalizedGeneratedColumnsMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V45__dashboard_normalized_generated_columns.sql"
    );

    @Test
    void migrationAddsDashboardGeneratedColumnsAndLookupIndexes() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("status_norm")
            .contains("risk_level_norm")
            .contains("risk_bucket_norm")
            .contains("llm_status_norm")
            .contains("llm_parse_status_norm")
            .contains("llm_model_label")
            .contains("repository_label")
            .contains("created_date")
            .contains("feedback_status_norm")
            .contains("generated always as");

        assertThat(sql)
            .contains("idx_review_task_dashboard_created_risk_norm")
            .contains("created_at, risk_bucket_norm, risk_level_norm, status_norm")
            .contains("idx_review_task_dashboard_created_day")
            .contains("created_at, created_date")
            .contains("idx_review_task_dashboard_created_llm_model_norm")
            .contains("created_at, llm_status_norm, llm_parse_status_norm, llm_model_label")
            .contains("idx_review_task_dashboard_created_llm_repo_norm")
            .contains("created_at, llm_status_norm, llm_parse_status_norm, repository_label")
            .contains("idx_review_finding_task_category_feedback_norm")
            .contains("task_id, category, feedback_status_norm");
    }
}
