package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;

/** Domain port for applying a repository policy without coupling review to a provider adapter. */
public interface RepositoryPolicyRuntime {

    ReviewPolicySettings applyLlmSettings(ReviewTask task, ReviewPolicySettings serverSettings);

    ReviewResult applyFindings(ReviewTask task, ReviewResult result);

    RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation preview(
        String organization,
        String repository,
        String headSha
    );
}
