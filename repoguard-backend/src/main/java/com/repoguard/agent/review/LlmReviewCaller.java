package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;

interface LlmReviewCaller {

    LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff);
}
