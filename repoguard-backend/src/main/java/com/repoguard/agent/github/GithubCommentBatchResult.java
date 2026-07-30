package com.repoguard.agent.github;

import java.util.List;

record GithubCommentBatchResult(List<GithubReviewCommentResult> results, int failedCount) {
}
