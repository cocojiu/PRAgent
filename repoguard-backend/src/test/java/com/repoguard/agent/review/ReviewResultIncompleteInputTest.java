package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewResultIncompleteInputTest {

    @Test
    void marksTruncatedInputAsPartialFallbackAndRequiresAtLeastMediumRisk() {
        ReviewResult result = ReviewResult.completed("LOW", List.of())
            .withIncompleteInput(
                "Pull request diff truncated: reasons=max_total_bytes",
                "diffTruncated=true"
            );

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.llmStatus()).isEqualTo("COMPLETED");
        assertThat(result.llmParseStatus()).isEqualTo("partial_fallback");
        assertThat(result.statusDetail()).contains("reasons=max_total_bytes");
        assertThat(result.llmPromptSummary()).isEqualTo("diffTruncated=true");
    }

    @Test
    void preservesExistingFallbackAndHigherRiskWhileAppendingBudgetEvidence() {
        ReviewResult result = ReviewResult.fallback("HIGH", "llm unavailable", List.of())
            .withIncompleteInput("diff truncated", "diffTruncated=true");

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.llmStatus()).isEqualTo("FALLBACK");
        assertThat(result.llmParseStatus()).isEqualTo("fallback");
        assertThat(result.statusDetail()).isEqualTo("llm unavailable; diff truncated");
    }
}
