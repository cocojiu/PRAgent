package com.repoguard.agent.review;

/** Result of reviewing one diff chunk, either through the LLM or rule fallback. */
record LlmChunkReviewOutcome(
    ReviewResult review,
    LlmCallResult callResult,
    LlmVerificationSummary verificationSummary
) {

    static LlmChunkReviewOutcome llm(ReviewResult parsed, LlmCallResult callResult) {
        return llm(parsed, callResult, LlmVerificationSummary.empty());
    }

    static LlmChunkReviewOutcome llm(
        ReviewResult parsed,
        LlmCallResult callResult,
        LlmVerificationSummary verificationSummary
    ) {
        return new LlmChunkReviewOutcome(parsed, callResult, verificationSummary);
    }

    static LlmChunkReviewOutcome fallback(ReviewResult ruleReview) {
        return new LlmChunkReviewOutcome(ruleReview, null, LlmVerificationSummary.empty());
    }
}
