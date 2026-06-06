package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;
import java.util.List;

public interface GithubPullRequestClient {

    GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task);

    List<GithubReviewCommentResult> publishPullRequestComments(ReviewTask task, List<GithubReviewCommentDraft> drafts);
}
