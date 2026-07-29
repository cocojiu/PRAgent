package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;

interface LlmReviewCaller {

    LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff);
}
