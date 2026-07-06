package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import java.util.Map;

interface PullRequestReviewRule {

    String id();

    default int order() {
        return 1000;
    }

    List<ReviewFindingResult> evaluate(
        GithubPullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules
    );
}
