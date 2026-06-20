package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewFindingResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFindingDeduplicatorTest {

    private final ReviewFindingDeduplicator deduplicator = new ReviewFindingDeduplicator();

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
}
