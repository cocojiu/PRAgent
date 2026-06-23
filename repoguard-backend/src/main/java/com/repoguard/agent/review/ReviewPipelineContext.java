package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;

record ReviewPipelineContext(
    ReviewTask task,
    GithubPullRequestDiff diff,
    ReviewPolicySettings settings,
    String promptSummary,
    long startedAtNanos,
    LlmReviewCaller llmReviewCaller
) {
}
