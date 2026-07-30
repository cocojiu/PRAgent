package com.repoguard.agent.review;

/** Result of reviewing one diff chunk, either through the LLM or rule fallback. */
record LlmChunkReviewOutcome(ReviewResult review, LlmCallResult callResult) {

    static LlmChunkReviewOutcome llm(ReviewResult parsed, LlmCallResult callResult) {
        return new LlmChunkReviewOutcome(parsed, callResult);
    }

    static LlmChunkReviewOutcome fallback(ReviewResult ruleReview) {
        return new LlmChunkReviewOutcome(ruleReview, null);
    }
}
