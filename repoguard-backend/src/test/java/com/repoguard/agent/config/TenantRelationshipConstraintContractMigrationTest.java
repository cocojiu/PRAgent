package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantRelationshipConstraintContractMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V77__tenant_relationship_constraint_contract.sql"
    );

    @Test
    void diagnosesEveryRelationshipBeforeReplacingLegacyForeignKeys() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase();
        assertThat(sql).contains(
            "create table flyway_v77_tenant_relation_violation",
            "check (violation_count = 0)"
        );
        assertThat(sql.indexOf("check (violation_count = 0)"))
            .isLessThan(sql.indexOf("drop foreign key"));

        List<String> relationships = List.of(
            "changed_file.task_id", "changed_file.attempt_id",
            "review_finding.task_id", "review_finding.attempt_id",
            "review_timeline.task_id", "review_execution_attempt.task_id",
            "github_comment_publication.task_id", "github_comment_publication.finding_id",
            "github_comment_publication_batch.task_id",
            "github_comment_publication_batch_item.batch_id",
            "github_comment_publication_batch_item.task_id",
            "github_comment_publication_batch_item.finding_id",
            "notification_event.task_id", "notification_event.batch_id",
            "notification_delivery_log.event_id", "notification_delivery_log.binding_id",
            "notification_delivery_log.task_id",
            "review_policy_promotion_evidence.rule_policy_snapshot_id",
            "review_policy_promotion_evidence.strategy_policy_snapshot_id",
            "review_strategy_policy_snapshot.source_snapshot_id"
        );
        assertThat(relationships).allSatisfy(name -> assertThat(sql).contains("'" + name + "'"));
    }

    @Test
    void installsTwentyCompositeForeignKeysAndNotificationIndexes() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase();
        List<String> constraints = List.of(
            "fk_changed_file_tenant_task", "fk_changed_file_tenant_attempt",
            "fk_review_finding_tenant_task", "fk_review_finding_tenant_attempt",
            "fk_review_timeline_tenant_task", "fk_review_attempt_tenant_task",
            "fk_github_comment_pub_tenant_task", "fk_github_comment_pub_tenant_finding",
            "fk_github_comment_batch_tenant_task", "fk_github_comment_item_tenant_batch",
            "fk_github_comment_item_tenant_task", "fk_github_comment_item_tenant_finding",
            "fk_notification_event_tenant_task", "fk_notification_event_tenant_batch",
            "fk_notification_delivery_tenant_event", "fk_notification_delivery_tenant_binding",
            "fk_notification_delivery_tenant_task", "fk_policy_evidence_tenant_rule",
            "fk_policy_evidence_tenant_strategy", "fk_strategy_policy_tenant_source"
        );
        assertThat(constraints).allSatisfy(name -> assertThat(sql).contains("constraint " + name));
        assertThat(sql).contains(
            "idx_notification_event_tenant_task (tenant_id, task_id)",
            "idx_notification_event_tenant_batch (tenant_id, batch_id)",
            "idx_notification_delivery_tenant_task (tenant_id, task_id)"
        );
        assertThat(sql).doesNotContain("delete from", "update ");
    }
}
