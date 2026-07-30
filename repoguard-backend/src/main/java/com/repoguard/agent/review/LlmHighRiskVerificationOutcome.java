package com.repoguard.agent.review;

record LlmHighRiskVerificationOutcome(
    ReviewResult review,
    LlmCallResult verificationUsage,
    LlmVerificationSummary summary
) {
}
