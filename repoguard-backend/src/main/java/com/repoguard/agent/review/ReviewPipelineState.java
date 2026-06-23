package com.repoguard.agent.review;

record ReviewPipelineState(
    ReviewPipelineContext context,
    ReviewResult llmReview,
    ReviewResult ruleReview,
    ReviewResult result,
    boolean completed
) {

    static ReviewPipelineState started(ReviewPipelineContext context) {
        return new ReviewPipelineState(context, null, null, null, false);
    }

    ReviewPipelineState withLlmReview(ReviewResult llmReview) {
        return new ReviewPipelineState(context, llmReview, ruleReview, result, false);
    }

    ReviewPipelineState withRuleReview(ReviewResult ruleReview) {
        return new ReviewPipelineState(context, llmReview, ruleReview, result, false);
    }

    ReviewPipelineState complete(ReviewResult result) {
        return new ReviewPipelineState(context, llmReview, ruleReview, result, true);
    }
}
