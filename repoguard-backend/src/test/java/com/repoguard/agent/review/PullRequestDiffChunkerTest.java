package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullRequestDiffChunkerTest {

    private final PullRequestDiffChunker chunker = DiffChunkingTestFixtures.chunker();

    @Test
    void chunkPrioritizesSensitiveFilesAndSplitsLargePullRequest() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff("octocat", "Hello-World", 9, List.of(
            file("src/main/java/com/example/UserController.java", 40, 10),
            file("README.md", 5, 1),
            file("src/main/resources/db/migration/V22__user_token.sql", 160, 20),
            file("src/main/java/com/example/security/AuthTokenFilter.java", 120, 30),
            file("src/main/resources/application-prod.yml", 20, 8),
            file(".github/workflows/deploy.yml", 35, 6),
            file("package.json", 10, 2),
            file("src/main/java/com/example/ReportService.java", 260, 20)
        ));

        List<PullRequestDiffChunk> chunks = chunker.chunk(diff);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().diff().files())
            .extracting(GithubChangedFile::filename)
            .contains("src/main/resources/db/migration/V22__user_token.sql");
        assertThat(chunks.getFirst().reasons()).contains("database_migration");
        assertThat(chunks)
            .flatExtracting(chunk -> chunk.diff().files())
            .extracting(GithubChangedFile::filename)
            .containsExactlyInAnyOrderElementsOf(diff.files().stream().map(GithubChangedFile::filename).toList());
    }

    @Test
    void chunkKeepsSmallStandardPullRequestTogether() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff("octocat", "Hello-World", 10, List.of(
            file("src/main/java/com/example/UserController.java", 20, 4),
            file("README.md", 5, 1)
        ));

        List<PullRequestDiffChunk> chunks = chunker.chunk(diff);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().fileCount()).isEqualTo(2);
        assertThat(chunks.getFirst().reasons()).contains("multi_file");
    }

    @Test
    void chunkSplitsLargeJavaFileBySemanticHunks() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff("octocat", "Hello-World", 11, List.of(
            file("src/main/java/com/example/order/OrderService.java", 760, 10, """
                @@ -10,6 +10,10 @@ public void approveOrder(Order order) {
                 public void approveOrder(Order order) {
                +    auditTrail.recordApproval(order.id());
                +    eventPublisher.publish(new OrderApproved(order.id()));
                 }
                @@ -80,6 +84,10 @@ public void cancelOrder(Order order) {
                 public void cancelOrder(Order order) {
                +    refundService.refund(order.paymentId());
                +    eventPublisher.publish(new OrderCancelled(order.id()));
                 }
                """)
        ));

        List<PullRequestDiffChunk> chunks = chunker.chunk(diff);

        assertThat(chunks).hasSize(2);
        assertThat(chunks)
            .allSatisfy(chunk -> {
                assertThat(chunk.fileCount()).isEqualTo(1);
                assertThat(chunk.reasons()).contains("semantic_scope", "code_scope");
            });
        assertThat(chunks.stream().mapToInt(PullRequestDiffChunk::additions).sum()).isEqualTo(760);
        assertThat(chunks.stream().mapToInt(PullRequestDiffChunk::deletions).sum()).isEqualTo(10);
        assertThat(chunks.stream().map(chunk -> chunk.diff().files().getFirst().patch()).toList())
            .anySatisfy(patch -> {
                assertThat(patch).contains("approveOrder");
                assertThat(patch).doesNotContain("cancelOrder");
            })
            .anySatisfy(patch -> {
                assertThat(patch).contains("cancelOrder");
                assertThat(patch).doesNotContain("approveOrder");
            });
    }

    @Test
    void chunkGroupsSameBusinessModuleBeforeStartingAnotherSemanticScope() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff("octocat", "Hello-World", 12, List.of(
            file("src/main/java/com/example/order/OrderController.java", 25, 2, javaPatch("createOrder")),
            file("src/main/java/com/example/order/OrderService.java", 28, 4, javaPatch("createOrder")),
            file("src/main/java/com/example/order/OrderRepository.java", 20, 3, javaPatch("createOrder")),
            file("src/main/java/com/example/payment/PaymentClient.java", 22, 2, javaPatch("charge")),
            file("src/main/java/com/example/payment/PaymentService.java", 24, 2, javaPatch("charge")),
            file("src/main/resources/application-prod.yml", 6, 1, """
                @@ -1,4 +1,6 @@ datasource:
                 datasource:
                +  maximum-pool-size: 20
                """),
            file("README.md", 4, 0, """
                @@ -2,3 +2,5 @@ Usage
                +Document new payment retry behavior.
                """)
        ));

        List<PullRequestDiffChunk> chunks = chunker.chunk(diff, chunkingPolicy(10, 100));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
            .anySatisfy(chunk -> {
                assertThat(chunk.diff().files())
                    .extracting(GithubChangedFile::filename)
                    .containsExactlyInAnyOrder(
                        "src/main/java/com/example/order/OrderController.java",
                        "src/main/java/com/example/order/OrderService.java",
                        "src/main/java/com/example/order/OrderRepository.java"
                    );
                assertThat(chunk.reasons()).contains("semantic_scope", "multi_file", "code_scope");
            });
        assertThat(chunks)
            .anySatisfy(chunk -> assertThat(chunk.diff().files())
                .extracting(GithubChangedFile::filename)
                .containsExactlyInAnyOrder(
                    "src/main/java/com/example/payment/PaymentClient.java",
                    "src/main/java/com/example/payment/PaymentService.java"
                ));
    }

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch for " + path);
    }

    private GithubChangedFile file(String path, int additions, int deletions, String patch) {
        return new GithubChangedFile(path, "modified", additions, deletions, patch);
    }

    private String javaPatch(String methodName) {
        return """
            @@ -10,6 +10,8 @@ public void %s() {
             public void %s() {
            +    validateRequest();
            +    publishDomainEvent();
             }
            """.formatted(methodName, methodName);
    }

    private ReviewPolicySettings chunkingPolicy(int maxFiles, int maxLines) {
        return new ReviewPolicySettings(
            true,
            true,
            "mock",
            "mock-model",
            "http://localhost",
            "test-key",
            30,
            BigDecimal.ZERO,
            1024,
            true,
            1,
            3,
            80,
            maxFiles,
            maxLines,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }
}
