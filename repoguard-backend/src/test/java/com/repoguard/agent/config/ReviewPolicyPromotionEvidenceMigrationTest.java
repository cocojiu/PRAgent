package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewPolicyPromotionEvidenceMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V63__review_policy_promotion_evidence.sql"
    );

    @Test
    void promotionEvidenceRemainsAuditableAfterRawFindingRetention() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("create table if not exists review_policy_promotion_evidence")
            .contains("rule_policy_snapshot_id")
            .contains("strategy_policy_snapshot_id")
            .contains("quality_baseline_version")
            .contains("quality_gate_version")
            .contains("baseline_calculated_at")
            .contains("sample_cutoff_at")
            .contains("total_samples")
            .contains("labeled_samples")
            .contains("confirmed_valid_samples")
            .contains("false_positive_samples")
            .contains("precision_rate")
            .contains("precision_wilson_lower_bound")
            .contains("sample_fingerprint")
            .contains("actor_user_id")
            .contains("trace_id")
            .contains("on delete restrict")
            .doesNotContain("on delete cascade");
    }

    @Test
    void eachRuleOrStrategySnapshotCanOwnOnlyOneImmutablePromotionEvidenceRow() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("unique key uk_review_policy_evidence_rule_snapshot (rule_policy_snapshot_id)")
            .contains("unique key uk_review_policy_evidence_strategy_snapshot (strategy_policy_snapshot_id)")
            .contains("constraint chk_review_policy_evidence_target check")
            .contains("constraint chk_review_policy_evidence_counts check");
    }
}
