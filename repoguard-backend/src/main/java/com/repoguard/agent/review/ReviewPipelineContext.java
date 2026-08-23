package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;

record ReviewPipelineContext(
    ReviewTask task,
    PullRequestDiff diff,
    ReviewPolicySettings settings,
    String promptSummary,
    long startedAtNanos,
    LlmReviewCaller llmReviewCaller,
    LlmReviewContext promptContext,
    ReviewDeadline deadline
) {

    ReviewPipelineContext(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewPolicySettings settings,
        String promptSummary,
        long startedAtNanos,
        LlmReviewCaller llmReviewCaller,
        LlmReviewContext promptContext
    ) {
        this(task, diff, settings, promptSummary, startedAtNanos, llmReviewCaller, promptContext, null);
    }

    ReviewPipelineContext(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewPolicySettings settings,
        String promptSummary,
        long startedAtNanos,
        LlmReviewCaller llmReviewCaller
    ) {
        this(task, diff, settings, promptSummary, startedAtNanos, llmReviewCaller, LlmReviewContext.legacy(), null);
    }

    ReviewPipelineContext {
        promptContext = promptContext == null ? LlmReviewContext.legacy() : promptContext;
    }
}
