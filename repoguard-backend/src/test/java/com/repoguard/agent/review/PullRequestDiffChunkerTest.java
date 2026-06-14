package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullRequestDiffChunkerTest {

    private final PullRequestDiffChunker chunker = new PullRequestDiffChunker();

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

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch for " + path);
    }
}
