package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmReviewPromptBuilderTest {

    private static final String COMMIT_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final LlmReviewPromptBuilder builder = new LlmReviewPromptBuilder();

    @Test
    void promptSummaryIncludesCountsAndOnlyFirstFiveSampleFiles() {
        PullRequestDiff diff = new PullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            COMMIT_SHA,
            List.of(
                file("src/A.java", 1, 0, "patch"),
                file("src/B.java", 2, 1, "patch"),
                file("src/C.java", null, 2, "patch"),
                file("src/D.java", 4, null, "patch"),
                file("src/E.java", 5, 5, "patch"),
                file("src/F.java", 6, 6, "patch")
            )
        );

        String summary = builder.promptSummary(diff);

        assertThat(summary).isEqualTo(
            "PR repo-guard-demo/spring-boot-demo#512; commit=" + COMMIT_SHA
                + "; files=6; additions=18; deletions=14; "
                + "sampleFiles=src/A.java, src/B.java, src/C.java, src/D.java, src/E.java, ...; "
                + "promptVersion=review-prompt-v2; contextVersion=review-context-v2; "
                + "schemaVersion=review-schema-v2; verifierVersion=high-risk-verifier-v1"
        );
    }

    @Test
    void chunkedPromptSummaryIncludesAggregateCountsAndDistinctReasons() {
        PullRequestDiff diff = new PullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            COMMIT_SHA,
            List.of(file("src/A.java", 1, 1, "patch"), file("src/B.java", 2, 2, "patch"))
        );
        List<PullRequestDiffChunk> chunks = List.of(
            new PullRequestDiffChunk(1, 2, diff, 1, 10, 3, List.of("too_many_files", "large_patch")),
            new PullRequestDiffChunk(2, 2, diff, 1, 5, 7, List.of("too_many_files", "security_sensitive"))
        );

        String summary = builder.chunkedPromptSummary(diff, chunks, 4, "HIGH", 1);

        assertThat(summary).isEqualTo(
            "PR repo-guard-demo/spring-boot-demo#512; commit=" + COMMIT_SHA
                + "; chunked=true; chunks=2; files=2; additions=15; "
                + "deletions=10; aggregateRisk=HIGH; aggregateFindings=4; failedChunks=1; "
                + "chunkReasons=too_many_files,large_patch,security_sensitive; "
                + "promptVersion=review-prompt-v2; contextVersion=review-context-v2; "
                + "schemaVersion=review-schema-v2; verifierVersion=high-risk-verifier-v1"
        );
    }

    @Test
    void buildPromptIncludesTaskAndCompactsLongPatch() {
        ReviewTask task = new ReviewTask();
        task.setTitle("Improve review flow");
        PullRequestDiff diff = new PullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            COMMIT_SHA,
            List.of(file("src/App.java", 1, 1, "a".repeat(7000)))
        );

        String prompt = builder.buildPrompt(task, diff);

        assertThat(prompt).contains("repo-guard-demo/spring-boot-demo#512");
        assertThat(prompt).contains("Commit SHA: " + COMMIT_SHA);
        assertThat(prompt).contains("Improve review flow");
        assertThat(prompt).contains("--- src/App.java");
        assertThat(prompt).contains("a".repeat(6000));
        assertThat(prompt).doesNotContain("a".repeat(6001));
        assertThat(prompt).contains(
            "review-prompt-v2",
            "review-schema-v2",
            "issueType",
            "preconditions",
            "relatedFiles",
            "blockingCandidate",
            "lineNumber 必须是当前 diff 中变更后的新增行"
        );
        assertThat(prompt).doesNotContain("\"isBlocking\":");
    }

    @Test
    void verificationPromptIsAdversarialAndCarriesCandidateAndVersionedContext() {
        ReviewTask task = new ReviewTask();
        task.setTitle("Protect admin route");
        String path = "src/AdminController.java";
        PullRequestChangedFile file = new PullRequestChangedFile(
            path,
            "modified",
            1,
            0,
            "@@ -0,0 +1,1 @@\n+void update() {}",
            ChangedFileContext.available(path, COMMIT_SHA, "void update() {}")
        );
        PullRequestDiff diff = new PullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            COMMIT_SHA,
            List.of(file)
        );
        ReviewFindingResult candidate = new ReviewFindingResult(
            "HIGH",
            "LLM",
            null,
            path,
            1,
            "The administrative route lacks authorization",
            "Require an administrative role",
            "HIGH",
            "The added route has no role guard",
            "Unauthorized state change",
            "Add @RequireRole",
            false,
            "SECURITY",
            "COMMENT",
            "pending",
            "MISSING_AUTHORIZATION",
            "An unauthenticated caller reaches the route",
            List.of("src/SecurityConfig.java"),
            true,
            "PENDING"
        );

        String prompt = builder.buildVerificationPrompt(task, diff, candidate, builder.buildContext(diff));

        assertThat(builder.verificationSystemPrompt()).contains("尝试推翻候选");
        assertThat(prompt).contains(
            "high-risk-verifier-v1",
            "请尝试推翻",
            "MISSING_AUTHORIZATION",
            "src/SecurityConfig.java",
            "addedLineValid",
            "protectionPresent",
            "Context version: review-context-v2",
            "[SOURCE] src/AdminController.java:L1-L1"
        );
    }

    private PullRequestChangedFile file(String path, Integer additions, Integer deletions, String patch) {
        return new PullRequestChangedFile(path, "modified", additions, deletions, patch);
    }
}
