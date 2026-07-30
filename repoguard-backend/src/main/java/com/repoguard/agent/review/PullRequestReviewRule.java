package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Map;

public interface PullRequestReviewRule {

    String id();

    default int order() {
        return 1000;
    }

    List<RuleMatch> evaluate(
        PullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules
    );
}
