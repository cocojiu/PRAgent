package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestHeadProvider;
import java.util.List;

public interface GithubPullRequestClient extends PullRequestHeadProvider {

    GithubRepositoryRef getConfiguredRepository();

    List<GithubPullRequestSummary> listOpenPullRequests();

    PullRequestDiff fetchPullRequestDiff(ReviewTask task);

    List<GithubReviewCommentResult> publishPullRequestComments(ReviewTask task, List<GithubReviewCommentDraft> drafts);
}
