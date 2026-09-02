package com.repoguard.agent.github.checks;

import com.repoguard.agent.entity.ReviewTask;

/** Resolves the explicit per-repository consent used in addition to the global feature flag. */
public interface GithubCheckRunPolicyProvider {

    boolean isEnabled(ReviewTask task);
}
