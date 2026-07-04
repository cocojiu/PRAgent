package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRuleReviewMergerTest {

    private final LlmRuleReviewMerger merger = new LlmRuleReviewMerger(new RiskLevelRanker());

    @Test
    void mergeReturnsLlmReviewWhenRuleReviewHasNoFindings() {
        ReviewResult llmReview = ReviewResult.completed(
            "LOW",
            List.of(new ReviewFindingResult("LOW", "LLM", null, "src/App.java", 10, "LLM finding", "Fix it"))
        );

        ReviewResult merged = merger.mergeWithRuleReview(llmReview, ReviewResult.completed("HIGH", List.of()));

        assertThat(merged).isSameAs(llmReview);
    }

    @Test
    void mergeAppendsRuleFindingsAndKeepsHighestRisk() {
        ReviewResult llmReview = ReviewResult.completed(
            "LOW",
            List.of(new ReviewFindingResult("LOW", "LLM", null, "src/App.java", 10, "LLM finding", "Fix it"))
        );
        ReviewResult ruleReview = ReviewResult.completed(
            "HIGH",
            List.of(new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/App.java", 12, "Rule finding", "Use logger"))
        );

        ReviewResult merged = merger.mergeWithRuleReview(llmReview, ruleReview);

        assertThat(merged.riskLevel()).isEqualTo("HIGH");
        assertThat(merged.findings()).extracting(ReviewFindingResult::source).containsExactly("LLM", "RULE");
    }

    @Test
    void hybridPromptSummaryIncludesRuleAndMergedFindingCounts() {
        ReviewResult ruleReview = ReviewResult.completed(
            "MEDIUM",
            List.of(
                new ReviewFindingResult("LOW", "RULE", "A", "a.java", 1, "A", "A"),
                new ReviewFindingResult("MEDIUM", "RULE", "B", "b.java", 2, "B", "B")
            )
        );
        ReviewResult merged = ReviewResult.completed(
            "MEDIUM",
            List.of(
                new ReviewFindingResult("LOW", "LLM", null, "c.java", 3, "C", "C"),
                new ReviewFindingResult("LOW", "RULE", "A", "a.java", 1, "A", "A"),
                new ReviewFindingResult("MEDIUM", "RULE", "B", "b.java", 2, "B", "B")
            )
        );

        String summary = merger.hybridPromptSummary("PR demo/repo#1; files=1", ruleReview, merged);

        assertThat(summary).isEqualTo("PR demo/repo#1; files=1; rulesApplied=true; ruleFindings=2; mergedFindings=3");
    }

    @Test
    void maxRiskPrefersHigherKnownRiskAndKeepsCurrentWhenCandidateIsUnknown() {
        assertThat(merger.maxRisk("LOW", "CRITICAL")).isEqualTo("CRITICAL");
        assertThat(merger.maxRisk("MEDIUM", "unknown")).isEqualTo("MEDIUM");
    }
}
