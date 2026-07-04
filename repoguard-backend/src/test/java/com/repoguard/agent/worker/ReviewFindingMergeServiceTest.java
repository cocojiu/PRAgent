package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewFindingResult;
import org.junit.jupiter.api.Test;

class ReviewFindingMergeServiceTest {

    private final ReviewFindingMergeService mergeService = new ReviewFindingMergeService();

    @Test
    void keepsStrongerFindingAsPrimaryAndMergesSupportingFields() {
        ReviewFindingResult merged = mergeService.merge(
            new ReviewFindingResult("LOW", "LLM", "LLM", "src/App.java", 10, "Use logger", "Replace stdout"),
            new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/App.java", 10, "Use logger", "Use structured logger")
        );

        assertThat(merged.severity()).isEqualTo("HIGH");
        assertThat(merged.source()).isEqualTo("LLM+RULE");
        assertThat(merged.ruleId()).isEqualTo("LLM / RG-JAVA-002");
        assertThat(merged.filePath()).isEqualTo("src/App.java");
        assertThat(merged.lineNumber()).isEqualTo(10);
        assertThat(merged.message()).isEqualTo("Use logger");
        assertThat(merged.recommendation()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(merged.confidence()).isEqualTo("HIGH");
        assertThat(merged.fixExample()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(merged.isBlocking()).isTrue();
        assertThat(merged.reviewDimension()).isEqualTo("LLM / PROJECT_RULE");
    }

    @Test
    void trimsAndAvoidsDuplicateTextWhenMergingFields() {
        ReviewFindingResult merged = mergeService.merge(
            finding("MEDIUM", " LLM ", "RG-1", " Same recommendation "),
            finding("MEDIUM", "llm", "rg-1", "same recommendation")
        );

        assertThat(merged.source()).isEqualTo("LLM");
        assertThat(merged.ruleId()).isEqualTo("RG-1");
        assertThat(merged.recommendation()).isEqualTo("Same recommendation");
    }

    private ReviewFindingResult finding(
        String severity,
        String source,
        String ruleId,
        String recommendation
    ) {
        return new ReviewFindingResult(
            severity,
            source,
            ruleId,
            "src/App.java",
            10,
            "Use logger",
            recommendation,
            "MEDIUM",
            "",
            "",
            recommendation,
            false,
            "PROJECT_RULE"
        );
    }
}
