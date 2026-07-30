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
        assertThat(finding.evidence()).contains(
            "LLM quality score=95",
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
            "MEDIUM",
            "",
            "",
            recommendation,
            false,
            "LLM"
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
