package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PullRequestDiffChunkFactoryTest {

    private final PullRequestDiffChunkFactory factory = new PullRequestDiffChunkFactory(
        new DiffChunkReasonBuilder(new DiffRiskClassifier())
    );

    @Test
    void buildsFileChunkWithAggregatedCountsAndReasons() {
        PullRequestChangedFile migration = file("src/main/resources/db/migration/V42__token.sql", 120, 10);
        PullRequestChangedFile security = file("src/main/java/com/example/security/AuthTokenFilter.java", null, 5);
        PullRequestDiff source = diff(List.of(migration, security));

        PullRequestDiffChunk chunk = factory.fileChunk(source, source.files(), 1, 2, DiffChunkingPolicy.defaults());

        assertThat(chunk.index()).isEqualTo(1);
        assertThat(chunk.total()).isEqualTo(2);
        assertThat(chunk.fileCount()).isEqualTo(2);
        assertThat(chunk.additions()).isEqualTo(120);
        assertThat(chunk.deletions()).isEqualTo(15);
        assertThat(chunk.diff().files()).containsExactly(migration, security);
        assertThat(chunk.diff().headSha()).isEqualTo("reviewed-commit");
        assertThat(chunk.reasons()).contains("multi_file", "database_migration", "security_sensitive");
    }

    @Test
    void buildsSemanticChunkWithDistinctFileCountAndSegmentCounts() {
        PullRequestChangedFile service = file("src/main/java/com/example/order/OrderService.java", 100, 10);
        PullRequestChangedFile controller = file("src/main/java/com/example/order/OrderController.java", 40, 4);
        PullRequestDiff source = diff(List.of(service, controller));

        PullRequestDiffChunk chunk = factory.semanticChunk(
            source,
            List.of(
                segment(service, "order", "approve", 60, 4),
                segment(service, "order", "cancel", 40, 6),
                segment(controller, "order", "approve", 20, 2)
            ),
            2,
            3,
            DiffChunkingPolicy.defaults()
        );

        assertThat(chunk.index()).isEqualTo(2);
        assertThat(chunk.total()).isEqualTo(3);
        assertThat(chunk.fileCount()).isEqualTo(2);
        assertThat(chunk.additions()).isEqualTo(120);
        assertThat(chunk.deletions()).isEqualTo(12);
        assertThat(chunk.diff().files()).containsExactly(service, service, controller);
        assertThat(chunk.diff().headSha()).isEqualTo("reviewed-commit");
        assertThat(chunk.reasons()).contains("semantic_scope", "multi_file", "code_scope");
    }

    private PullRequestDiff diff(List<PullRequestChangedFile> files) {
        return new PullRequestDiff("octocat", "repo", 17, "reviewed-commit", files);
    }

    private PullRequestChangedFile file(String path, Integer additions, Integer deletions) {
        return new PullRequestChangedFile(path, "modified", additions, deletions, "@@ patch");
    }

    private SemanticDiffSegment segment(
        PullRequestChangedFile file,
        String groupKey,
        String semanticKey,
        int additions,
        int deletions
    ) {
        return new SemanticDiffSegment(file, groupKey, semanticKey, "code_scope", 1, additions, deletions);
    }
}
