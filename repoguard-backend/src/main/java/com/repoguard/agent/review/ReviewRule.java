package com.repoguard.agent.review;

import java.util.Optional;

interface ReviewRule {

    String id();

    Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context);
}
