package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewPolicyVersionGovernanceMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V58__review_policy_version_governance.sql"
    );

    @Test
    void migrationAddsTraceableFindingAndRollbackSnapshotSchema() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("create table if not exists review_rule_policy_snapshot")
            .contains("unique key uk_review_rule_policy_snapshot_version (rule_id, policy_version)")
            .contains("create table if not exists review_strategy_policy_snapshot")
            .contains("unique key uk_review_strategy_policy_single_active (active_guard)")
            .contains("'observe'")
            .contains("replay_verified")
            .contains("detector_version")
            .contains("rule_config_version")
            .contains("prompt_version")
            .contains("context_version")
            .contains("schema_version")
            .contains("verifier_version")
            .contains("aggregation_version")
            .contains("policy_version")
            .contains("original_severity")
            .contains("original_confidence")
            .contains("original_is_blocking")
            .contains("downgrade_reason")
            .contains("block_reason")
            .contains("anchor_type");
    }
}
