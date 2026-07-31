package com.repoguard.agent.review;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class LlmRuleReviewMerger {

    private final RiskLevelRanker riskLevelRanker;
    private final ReviewFindingSemanticDeduplicator findingDeduplicator;
    private final ServerRiskAggregator riskAggregator;

    LlmRuleReviewMerger(RiskLevelRanker riskLevelRanker) {
        this(riskLevelRanker, new ReviewFindingSemanticDeduplicator(), new ServerRiskAggregator());
    }

    @Autowired
    LlmRuleReviewMerger(
        RiskLevelRanker riskLevelRanker,
        ReviewFindingSemanticDeduplicator findingDeduplicator,
        ServerRiskAggregator riskAggregator
    ) {
        this.riskLevelRanker = Objects.requireNonNull(riskLevelRanker, "riskLevelRanker");
        this.findingDeduplicator = Objects.requireNonNull(findingDeduplicator, "findingDeduplicator");
        this.riskAggregator = Objects.requireNonNull(riskAggregator, "riskAggregator");
    }

    ReviewResult mergeWithRuleReview(ReviewResult llmReview, ReviewResult ruleReview) {
        List<ReviewFindingResult> findings = new java.util.ArrayList<>();
        if (llmReview != null && llmReview.findings() != null) {
            findings.addAll(llmReview.findings());
        }
        if (ruleReview != null && ruleReview.findings() != null) {
            findings.addAll(ruleReview.findings());
        }
        List<ReviewFindingResult> uniqueFindings = findingDeduplicator.deduplicate(findings);
        return ReviewResult.completed(riskAggregator.aggregate(uniqueFindings), uniqueFindings);
    }

    String hybridPromptSummary(String promptSummary, ReviewResult ruleReview, ReviewResult merged) {
        int ruleFindings = ruleReview == null || ruleReview.findings() == null ? 0 : ruleReview.findings().size();
        int mergedFindings = merged == null || merged.findings() == null ? 0 : merged.findings().size();
        return promptSummary
            + "; rulesApplied=true"
            + "; ruleFindings=" + ruleFindings
            + "; mergedFindings=" + mergedFindings;
    }

    String maxRisk(String current, String candidate) {
        return riskLevelRanker.higher(current, candidate);
    }
}
