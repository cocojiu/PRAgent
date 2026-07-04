package com.repoguard.agent.review;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class LlmRuleReviewMerger {

    private final RiskLevelRanker riskLevelRanker;

    LlmRuleReviewMerger(RiskLevelRanker riskLevelRanker) {
        this.riskLevelRanker = Objects.requireNonNull(riskLevelRanker, "riskLevelRanker");
    }

    ReviewResult mergeWithRuleReview(ReviewResult llmReview, ReviewResult ruleReview) {
        if (ruleReview == null || ruleReview.findings() == null || ruleReview.findings().isEmpty()) {
            return llmReview;
        }
        List<ReviewFindingResult> findings = new java.util.ArrayList<>();
        if (llmReview.findings() != null) {
            findings.addAll(llmReview.findings());
        }
        findings.addAll(ruleReview.findings());
        return ReviewResult.completed(maxRisk(llmReview.riskLevel(), ruleReview.riskLevel()), findings);
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
