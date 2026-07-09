package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewFindingRuleStatOperationalIndexMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V53__review_finding_rule_stat_operational_indexes.sql"
    );

    @Test
    void migrationAddsCategoryFirstIndexesForGlobalRuleStatistics() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("review_finding")
            .contains("idx_review_finding_category_rule")
            .contains("review_finding add key idx_review_finding_category_rule (category, rule_id)")
            .contains("idx_review_finding_category_feedback_norm")
            .contains("review_finding add key idx_review_finding_category_feedback_norm (category, feedback_status_norm)");
    }
}
