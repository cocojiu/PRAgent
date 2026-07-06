package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffChunkReasonBuilderTest {

    private final DiffChunkReasonBuilder reasonBuilder = new DiffChunkReasonBuilder(new DiffRiskClassifier());

    @Test
    void fileReasonsIncludeChurnMultiFileAndRiskSignals() {
        List<String> reasons = reasonBuilder.fileReasons(
            List.of(
                file("src/main/resources/db/migration/V39__session.sql", 220, 20),
                file("src/main/java/com/example/security/AuthTokenFilter.java", 40, 4)
            ),
            new DiffChunkingPolicy(4, 400, 6, 700)
        );

        assertThat(reasons)
            .contains("multi_file", "large_churn", "database_migration", "security_sensitive");
    }

    @Test
    void fileReasonsFallBackToStandardForSingleLowRiskFile() {
        List<String> reasons = reasonBuilder.fileReasons(
            List.of(file("src/main/java/com/example/UserService.java", 10, 2)),
            DiffChunkingPolicy.defaults()
        );

        assertThat(reasons).containsExactly("standard");
    }

    @Test
    void semanticReasonsDescribeScopeSplitsAndSegmentRisk() {
        GithubChangedFile file = file("src/main/java/com/example/security/AuthTokenFilter.java", 260, 12);

        List<String> reasons = reasonBuilder.semanticReasons(
            List.of(file),
            List.of(
                segment(file, "security", "authorize", "code_scope", 1, 130, 6),
                segment(file, "security", "refresh", "code_scope", 1, 130, 6)
            ),
            new DiffChunkingPolicy(4, 400, 6, 700)
        );

        assertThat(reasons)
            .contains("semantic_scope", "split_file_scope", "large_churn", "code_scope", "security_sensitive");
    }

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch");
    }

    private SemanticDiffSegment segment(
        GithubChangedFile file,
        String groupKey,
        String semanticKey,
        String semanticReason,
        int priority,
        int additions,
        int deletions
    ) {
        return new SemanticDiffSegment(file, groupKey, semanticKey, semanticReason, priority, additions, deletions);
    }
}
