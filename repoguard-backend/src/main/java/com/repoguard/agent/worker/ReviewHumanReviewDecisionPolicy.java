package com.repoguard.agent.worker;

import com.repoguard.agent.review.RiskLevelRanker;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewHumanReviewDecisionPolicy {

    private static final String HUMAN_REVIEW_THRESHOLD = "MEDIUM";

    private final RiskLevelRanker riskLevelRanker;

    ReviewHumanReviewDecisionPolicy(RiskLevelRanker riskLevelRanker) {
        this.riskLevelRanker = Objects.requireNonNull(riskLevelRanker, "riskLevelRanker");
    }

    boolean requiresHumanReview(String riskLevel) {
        return riskLevelRanker.atLeast(riskLevel, HUMAN_REVIEW_THRESHOLD);
    }
}
