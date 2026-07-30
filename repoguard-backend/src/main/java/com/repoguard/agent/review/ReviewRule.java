package com.repoguard.agent.review;

import java.util.Optional;

public interface ReviewRule {

    String id();

    default int order() {
        return 1000;
    }

    Optional<RuleMatch> evaluate(ReviewRuleLineContext context);
}
