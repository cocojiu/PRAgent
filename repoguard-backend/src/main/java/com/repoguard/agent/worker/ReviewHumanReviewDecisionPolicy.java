package com.repoguard.agent.worker;

import com.repoguard.agent.config.ReviewHumanReviewProperties;
import com.repoguard.agent.review.HumanReviewPolicyEvaluator;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.RiskLevelRanker;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ReviewHumanReviewDecisionPolicy {

    private final HumanReviewPolicyEvaluator evaluator;

    ReviewHumanReviewDecisionPolicy(RiskLevelRanker riskLevelRanker) {
        this(new HumanReviewPolicyEvaluator(riskLevelRanker, new ReviewHumanReviewProperties()));
    }

    @Autowired
    ReviewHumanReviewDecisionPolicy(HumanReviewPolicyEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    boolean requiresHumanReview(ReviewResult reviewResult) {
        return evaluator.requiresHumanReview(reviewResult);
    }

    boolean requiresHumanReview(String riskLevel) {
        return evaluator.requiresHumanReview(riskLevel);
    }
}
