package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.RiskLevelRanker;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFindingDeduplicatorTest {

    private final ReviewFindingDeduplicator deduplicator = new ReviewFindingDeduplicator(
        new ReviewFindingDeduplicationKeyResolver(),
        new ReviewFindingMergeService(new RiskLevelRanker())
    );

    @Test
    void returnsEmptyListForNullOrEmptyFindings() {
        assertThat(deduplicator.deduplicate(null)).isEmpty();
        assertThat(deduplicator.deduplicate(List.of())).isEmpty();
    }

    @Test
    void mergesDuplicateFindingsByFileLineAndMessage() {
        List<ReviewFindingResult> findings = deduplicator.deduplicate(List.of(
            new ReviewFindingResult("LOW", "LLM", "LLM", "src/App.java", 10, "Use logger", "Replace stdout"),
            new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/App.java", 10, "  use   logger  ", "Use structured logger")
        ));

        assertThat(findings).hasSize(1);
        ReviewFindingResult finding = findings.getFirst();
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.source()).isEqualTo("LLM+RULE");
        assertThat(finding.ruleId()).isEqualTo("LLM / RG-JAVA-002");
        assertThat(finding.recommendation()).isEqualTo("Replace stdout / Use structured logger");
    }

    @Test
    void keepsDistinctFindingsInInputOrder() {
        List<ReviewFindingResult> findings = deduplicator.deduplicate(List.of(
            new ReviewFindingResult("LOW", "LLM", "LLM", "src/App.java", 10, "Use logger", "Replace stdout"),
            new ReviewFindingResult("MEDIUM", "RULE", "RG-JAVA-003", "src/App.java", 11, "Avoid sleep", "Use awaitility")
        ));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).message()).isEqualTo("Use logger");
        assertThat(findings.get(1).message()).isEqualTo("Avoid sleep");
    }

    @Test
    void mergesFindingsWithSameSemanticFingerprintAcrossDifferentWording() {
        List<ReviewFindingResult> findings = deduplicator.deduplicate(List.of(
            finding("MEDIUM", "LLM", "LLM", "src/main/java/com/example/AdminController.java", 20,
                "Admin endpoint can be called without authorization",
                "Add role based access control"),
            finding("HIGH", "RULE", "RG-AUTH-001", "src/main/java/com/example/AdminController.java", 22,
                "Missing permission gate on admin API",
                "Require ADMIN role before executing the handler")
        ));

        assertThat(findings).hasSize(1);
        ReviewFindingResult finding = findings.getFirst();
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.source()).isEqualTo("LLM+RULE");
        assertThat(finding.message()).isEqualTo("Missing permission gate on admin API");
        assertThat(finding.recommendation()).contains("Add role based access control", "Require ADMIN role");
    }

    @Test
    void keepsDifferentSemanticIssuesOnSameFileAndLineSeparate() {
        List<ReviewFindingResult> findings = deduplicator.deduplicate(List.of(
            finding("LOW", "LLM", "LLM", "src/main/java/com/example/App.java", 30,
                "System.out.println is used in request handling",
                "Use structured logging"),
            finding("MEDIUM", "RULE", "RG-JAVA-003", "src/main/java/com/example/App.java", 31,
                "Thread.sleep blocks the worker thread",
                "Replace fixed sleep with async waiting")
        ));

        assertThat(findings).hasSize(2);
        assertThat(findings).extracting(ReviewFindingResult::message)
            .containsExactly("System.out.println is used in request handling", "Thread.sleep blocks the worker thread");
    }

    @Test
    void keepsGenericFindingsDistinctWhenStableTermsDiffer() {
        List<ReviewFindingResult> findings = deduplicator.deduplicate(List.of(
            finding("LOW", "LLM", "LLM", "src/main/java/com/example/App.java", 40,
                "Button label is unclear",
                "Use a clear action label"),
            finding("LOW", "LLM", "LLM", "src/main/java/com/example/App.java", 42,
                "Error message hides retry guidance",
                "Explain how to retry")
        ));

        assertThat(findings).hasSize(2);
    }

    private ReviewFindingResult finding(
        String severity,
        String source,
        String ruleId,
        String filePath,
        Integer lineNumber,
        String message,
        String recommendation
    ) {
        return new ReviewFindingResult(severity, source, ruleId, filePath, lineNumber, message, recommendation);
    }
}
