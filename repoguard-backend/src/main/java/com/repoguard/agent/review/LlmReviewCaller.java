package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;

interface LlmReviewCaller {

    LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff);

    default LlmCallResult callLlm(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        LlmReviewContext context
    ) {
        return callLlm(settings, task, diff);
    }

    default boolean supportsHighRiskVerification() {
        return false;
    }

    default LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff,
        LlmReviewContext context, java.util.function.LongSupplier remaining) {
        return callLlm(settings, task, diff, context);
    }

    default LlmCallResult verifyHighRisk(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff,
        ReviewFindingResult candidate, LlmReviewContext context, java.util.function.LongSupplier remaining) {
        return verifyHighRisk(settings, task, diff, candidate, context);
    }

    default LlmCallResult verifyHighRisk(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        ReviewFindingResult candidate,
        LlmReviewContext context
    ) {
        throw new UnsupportedOperationException("High-risk verification is not supported by this caller");
    }
}
