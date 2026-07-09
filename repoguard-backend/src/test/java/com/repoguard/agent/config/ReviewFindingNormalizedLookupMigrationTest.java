package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewFindingNormalizedLookupMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V51__review_finding_normalized_lookup_columns.sql"
    );

    @Test
    void migrationAddsSeverityNormalizationColumnAndLookupIndex() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("review_finding")
            .contains("severity_norm")
            .contains("generated always as")
            .contains("lower(coalesce(nullif(trim(severity)")
            .contains("idx_review_finding_task_category_severity_norm")
            .contains("task_id, category, severity_norm");
    }
}
