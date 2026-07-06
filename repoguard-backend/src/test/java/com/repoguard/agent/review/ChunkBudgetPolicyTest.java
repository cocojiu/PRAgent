package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkBudgetPolicyTest {

    private final ChunkBudgetPolicy budgetPolicy = new ChunkBudgetPolicy(new DiffRiskClassifier());

    @Test
    void requiresChunkingWhenFileOrLineThresholdIsExceeded() {
        DiffChunkingPolicy policy = new DiffChunkingPolicy(4, 450, 2, 100);

        assertThat(budgetPolicy.requiresChunking(List.of(
            file("src/main/java/A.java", 10, 1),
            file("src/main/java/B.java", 10, 1),
            file("src/main/java/C.java", 10, 1)
        ), policy)).isTrue();
        assertThat(budgetPolicy.requiresChunking(List.of(
            file("src/main/java/A.java", 101, 0)
        ), policy)).isTrue();
    }

    @Test
    void requiresChunkingWhenRiskFileAppearsInMultiFilePullRequest() {
        assertThat(budgetPolicy.requiresChunking(List.of(
            file("src/main/resources/db/migration/V39__session.sql", 5, 1),
            file("src/main/java/com/example/UserService.java", 5, 1)
        ), DiffChunkingPolicy.defaults())).isTrue();
    }

    @Test
    void keepsSmallStandardPullRequestTogether() {
        assertThat(budgetPolicy.requiresChunking(List.of(
            file("src/main/java/com/example/UserService.java", 5, 1)
        ), DiffChunkingPolicy.defaults())).isFalse();
    }

    @Test
    void detectsSegmentChunkBudgetOverflow() {
        DiffChunkingPolicy policy = new DiffChunkingPolicy(2, 60, 6, 700);
        List<SemanticDiffSegment> current = List.of(
            segment("src/main/java/A.java", "a", 20),
            segment("src/main/java/B.java", "b", 20)
        );

        assertThat(budgetPolicy.exceedsChunkBudget(current, 40, segment("src/main/java/C.java", "c", 10), policy))
            .isTrue();
        assertThat(budgetPolicy.exceedsChunkBudget(List.of(
            segment("src/main/java/A.java", "a", 40)
        ), 40, segment("src/main/java/A.java", "a2", 25), policy)).isTrue();
    }

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch");
    }

    private SemanticDiffSegment segment(String path, String semanticKey, int lines) {
        return new SemanticDiffSegment(
            file(path, lines, 0),
            "group",
            semanticKey,
            "code_scope",
            5,
            lines,
            0
        );
    }
}
