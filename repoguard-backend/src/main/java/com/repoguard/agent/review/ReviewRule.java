package com.repoguard.agent.review;

import java.util.Optional;

public interface ReviewRule {

    String id();

    default String version() {
        return id().trim().toLowerCase(java.util.Locale.ROOT) + "-detector-v2";
    }

    default int order() {
        return 1000;
    }

    Optional<RuleMatch> evaluate(ReviewRuleLineContext context);
}
