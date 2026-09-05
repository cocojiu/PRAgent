package com.repoguard.agent.review;

/** Result of reviewing one diff chunk, either through the LLM or rule fallback. */
record LlmChunkReviewOutcome(
    ReviewResult review,
    LlmCallResult callResult,
    LlmVerificationSummary verificationSummary,
    String failureCategory
) {

    static LlmChunkReviewOutcome llm(ReviewResult parsed, LlmCallResult callResult) {
        return llm(parsed, callResult, LlmVerificationSummary.empty());
    }

    static LlmChunkReviewOutcome llm(
        ReviewResult parsed,
        LlmCallResult callResult,
        LlmVerificationSummary verificationSummary
    ) {
        return new LlmChunkReviewOutcome(parsed, callResult, verificationSummary, null);
    }

    static LlmChunkReviewOutcome fallback(ReviewResult ruleReview) {
        return fallback(ruleReview, LlmChunkReviewFallbackHandler.CHUNK_PARTIAL_FAILURE_CATEGORY);
    }

    static LlmChunkReviewOutcome fallback(ReviewResult ruleReview, String failureCategory) {
        return new LlmChunkReviewOutcome(
            ruleReview,
            null,
            LlmVerificationSummary.empty(),
            failureCategory == null || failureCategory.isBlank()
                ? LlmChunkReviewFallbackHandler.CHUNK_PARTIAL_FAILURE_CATEGORY
                : failureCategory.trim().toLowerCase(java.util.Locale.ROOT)
        );
    }
}
