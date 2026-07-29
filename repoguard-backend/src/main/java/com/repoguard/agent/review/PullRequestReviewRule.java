package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Map;

interface PullRequestReviewRule {

    String id();

    default int order() {
        return 1000;
    }

    List<ReviewFindingResult> evaluate(
        PullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules
    );
}
