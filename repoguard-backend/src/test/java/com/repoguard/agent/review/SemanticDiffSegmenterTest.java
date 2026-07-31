package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticDiffSegmenterTest {

    private final SemanticDiffSegmenter segmenter = DiffChunkingTestFixtures.segmenter();

    @Test
    void splitsJavaPatchByHunkAndAllocatesSourceLineCounts() {
        String path = "src/main/java/com/example/order/OrderService.java";
        ChangedFileContext context = ChangedFileContext.available(path, "head-a", "class OrderService {}");
        PullRequestChangedFile file = new PullRequestChangedFile(
            path,
            "modified",
            10,
            2,
            """
                @@ -10,6 +10,10 @@ public void approveOrder(Order order) {
                 public void approveOrder(Order order) {
                -    validate(order);
                +    auditTrail.recordApproval(order.id());
                +    eventPublisher.publish(new OrderApproved(order.id()));
                 }
                @@ -80,6 +84,10 @@ public void cancelOrder(Order order) {
                 public void cancelOrder(Order order) {
                -    order.cancel();
                +    refundService.refund(order.paymentId());
                 }
                """,
            context
        );

        List<SemanticDiffSegment> segments = segmenter.segments(file);

        assertThat(segments).hasSize(2);
        assertThat(segments).extracting(SemanticDiffSegment::semanticReason)
            .containsOnly("code_scope");
        assertThat(segments).extracting(SemanticDiffSegment::semanticKey)
            .anySatisfy(key -> assertThat(key).contains("order", "approveorder"))
            .anySatisfy(key -> assertThat(key).contains("order", "cancelorder"));
        assertThat(segments).extracting(SemanticDiffSegment::additions)
            .containsExactly(7, 3);
        assertThat(segments).extracting(SemanticDiffSegment::deletions)
            .containsExactly(1, 1);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.file().context()).isSameAs(context));
    }

    @Test
    void resolvesSqlAndConfigScopes() {
        SemanticDiffSegment sql = segmenter.segments(new PullRequestChangedFile(
            "src/main/resources/db/migration/V99__drop_legacy_table.sql",
            "modified",
            1,
            0,
            """
                @@ -1,1 +1,2 @@ migration
                +DROP TABLE legacy_token;
                """
        )).getFirst();
        SemanticDiffSegment config = segmenter.segments(new PullRequestChangedFile(
            "src/main/resources/application-prod.yml",
            "modified",
            1,
            0,
            """
                @@ -1,4 +1,6 @@ datasource:
                 datasource:
                +  maximum-pool-size: 20
                """
        )).getFirst();

        assertThat(sql.semanticReason()).isEqualTo("sql_statement");
        assertThat(sql.semanticKey()).contains("database", "drop");
        assertThat(config.semanticReason()).isEqualTo("config_section");
        assertThat(config.semanticKey()).contains("config", "datasource");
    }
}
