package com.repoguard.agent.scm;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;

/**
 * Provider-neutral source-control contract. Provider adapters own protocol details;
 * review orchestration only consumes this small contract.
 */
public interface ScmProvider {

    String providerKey();

    ScmIntegrationSettings settings();

    ScmRepositoryRef configuredRepository();

    List<ScmChangeRequestSummary> listOpenChangeRequests();

    PullRequestDiff fetchPullRequestDiff(ReviewTask task);

    String fetchPullRequestHeadSha(ReviewTask task);

    ScmCommentResult publishComment(ReviewTask task, ScmCommentDraft draft);

    ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request);
}
