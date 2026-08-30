package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantRelationshipConstraintExpansionMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V76__tenant_relationship_constraint_expansion.sql"
    );

    @Test
    void expandsEveryParentCandidateKeyNeededByTenantRelationships() throws IOException {
        assertThat(readMigration())
            .contains("unique key uk_review_task_tenant_id (tenant_id, id)")
            .contains("unique key uk_review_attempt_tenant_id (tenant_id, id)")
            .contains("unique key uk_review_finding_tenant_id (tenant_id, id)")
            .contains("unique key uk_github_comment_batch_tenant_id (tenant_id, id)")
            .contains("unique key uk_notification_event_tenant_id (tenant_id, id)")
            .contains("unique key uk_notification_binding_tenant_id (tenant_id, id)")
            .contains("unique key uk_rule_policy_snapshot_tenant_id (tenant_id, id)")
            .contains("unique key uk_strategy_policy_snapshot_tenant_id (tenant_id, id)");
    }

    @Test
    void expandsMissingChildIndexesWithTenantAsTheLeadingColumn() throws IOException {
        assertThat(readMigration())
            .contains("key idx_review_finding_tenant_attempt (tenant_id, attempt_id)")
            .contains("key idx_changed_file_tenant_attempt (tenant_id, attempt_id)")
            .contains("key idx_github_comment_pub_tenant_finding (tenant_id, finding_id)")
            .contains("key idx_github_comment_item_tenant_task (tenant_id, task_id)")
            .contains("key idx_github_comment_item_tenant_finding (tenant_id, finding_id)")
            .contains("key idx_notification_delivery_tenant_binding (tenant_id, binding_id)")
            .contains("key idx_policy_evidence_tenant_rule_snapshot (tenant_id, rule_policy_snapshot_id)")
            .contains(
                "key idx_policy_evidence_tenant_strategy_snapshot "
                    + "(tenant_id, strategy_policy_snapshot_id)"
            )
            .contains("key idx_strategy_policy_tenant_source (tenant_id, source_snapshot_id)");
    }

    @Test
    void expandPhaseKeepsLegacyConstraintsAndDoesNotRewriteRows() throws IOException {
        assertThat(readMigration())
            .doesNotContain("drop foreign key")
            .doesNotContain("drop index")
            .doesNotContain("drop key")
            .doesNotContain("update ")
            .doesNotContain("delete ")
            .doesNotContain("insert ");
    }

    private String readMigration() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
}
