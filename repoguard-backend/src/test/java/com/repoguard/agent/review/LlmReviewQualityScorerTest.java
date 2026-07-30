package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmReviewQualityScorerTest {

    private final LlmReviewQualityScorer scorer = new LlmReviewQualityScorer();

    @Test
    void assignsHighConfidenceWhenFindingReferencesAddedDiffLine() {
        ReviewResult result = scorer.score(
            ReviewResult.completed("HIGH", List.of(finding(
                "src/main/java/com/example/AdminController.java",
                12,
                "Admin endpoint is missing authorization checks",
                "Require an ADMIN role before executing the handler"
            ))),
            diff("""
                @@ -8,6 +10,8 @@ public void updateSettings() {
                 public void updateSettings() {
                +    audit.log("settings update");
                +    saveSettings();
                 }
                """)
        );

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(finding.lineNumber()).isEqualTo(12);
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.verificationStatus()).isEqualTo("PENDING");
        assertThat(finding.isBlocking()).isFalse();
        assertThat(finding.evidence()).contains(
            "LLM quality score=100",
            "diffFile=matched",
            "diffLine=added_line"
        );
    }

    @Test
    void clearsLineNumberAndDowngradesConfidenceWhenFileIsMissingFromDiff() {
        ReviewResult result = scorer.score(
            ReviewResult.completed("HIGH", List.of(finding(
                "src/main/java/com/example/OtherController.java",
                12,
                "Admin endpoint is missing authorization checks",
                "Require an ADMIN role before executing the handler"
            ))),
            diff("""
                @@ -8,6 +10,8 @@ public void updateSettings() {
                 public void updateSettings() {
                +    audit.log("settings update");
                +    saveSettings();
                 }
                """)
        );

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(finding.lineNumber()).isNull();
        assertThat(finding.confidence()).isEqualTo("LOW");
        assertThat(finding.evidence()).contains("diffFile=missing", "diffLine=file_not_in_diff");
    }

    @Test
    void clearsLineNumberWhenLineDoesNotPointToAddedDiffLine() {
        ReviewResult result = scorer.score(
            ReviewResult.completed("MEDIUM", List.of(finding(
                "src/main/java/com/example/AdminController.java",
                14,
                "Admin endpoint is missing authorization checks",
                "Require an ADMIN role before executing the handler"
            ))),
            diff("""
                @@ -8,6 +10,8 @@ public void updateSettings() {
                 public void updateSettings() {
                +    audit.log("settings update");
                +    saveSettings();
                 }
                """)
        );

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(finding.lineNumber()).isNull();
        assertThat(finding.confidence()).isEqualTo("MEDIUM");
        assertThat(finding.evidence()).contains("diffFile=matched", "diffLine=not_changed_line");
    }

    @Test
    void missingExactHeadContextRejectsHighRiskPrecheckEvenWithValidAddedLine() {
        ReviewFindingResult candidate = finding(
            "src/main/java/com/example/AdminController.java",
            12,
            "Admin endpoint is missing authorization checks",
            "Require an ADMIN role before executing the handler"
        );
        LlmReviewContext context = new LlmReviewContext(
            List.of(),
            "",
            List.of(new LlmReviewContext.ContextLimitation(
                candidate.filePath(),
                "UNAVAILABLE",
                "fetch_failed"
            )),
            false,
            24_000,
            8
        );

        ReviewFindingResult finding = scorer.score(
            ReviewResult.completed("HIGH", List.of(candidate)),
            diff("""
                @@ -8,6 +10,8 @@ public void updateSettings() {
                 public void updateSettings() {
                +    audit.log("settings update");
                +    saveSettings();
                 }
                """),
            context
        ).findings().getFirst();

        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.confidence()).isEqualTo("MEDIUM");
        assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
        assertThat(finding.verificationStatus()).isEqualTo("PRECHECK_REJECTED");
        assertThat(finding.blockingCandidate()).isFalse();
        assertThat(finding.evidence()).contains("LLM quality score=65", "context=unavailable");
    }

    @Test
    void leavesRuleFindingsUnchanged() {
        ReviewFindingResult ruleFinding = new ReviewFindingResult(
            "HIGH",
            "RULE",
            "RG-AUTH-001",
            "src/main/java/com/example/AdminController.java",
            14,
            "Rule finding",
            "Fix it"
        );

        ReviewResult result = scorer.score(ReviewResult.completed("HIGH", List.of(ruleFinding)), diff(""));

        assertThat(result.findings().getFirst()).isSameAs(ruleFinding);
    }

    private ReviewFindingResult finding(String filePath, Integer lineNumber, String message, String recommendation) {
        return new ReviewFindingResult(
            "HIGH",
            "LLM",
            null,
            filePath,
            lineNumber,
            message,
            recommendation,
            "HIGH",
            "POST /admin reaches the handler without an authorization guard",
            "Unauthorized callers can invoke the administrative write operation",
            recommendation,
            false,
            "SECURITY",
            EnforcementMode.OBSERVE.name(),
            "llm_candidate_unscored",
            "MISSING_AUTHORIZATION",
            "The route is reachable without an upstream authorization filter",
            List.of("src/main/java/com/example/SecurityConfig.java"),
            true,
            LlmVerificationStatus.NOT_REQUIRED.name()
        );
    }

    private PullRequestDiff diff(String patch) {
        return new PullRequestDiff("octocat", "Hello-World", 1, List.of(
            new PullRequestChangedFile(
                "src/main/java/com/example/AdminController.java",
                "modified",
                2,
                0,
                patch
            )
        ));
    }
}
