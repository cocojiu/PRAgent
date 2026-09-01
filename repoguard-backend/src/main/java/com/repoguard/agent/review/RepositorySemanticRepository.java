package com.repoguard.agent.review;

import java.util.Set;

/** Source-control neutral port for a bounded default-branch semantic snapshot. */
public interface RepositorySemanticRepository {

    RepositorySemanticSnapshot fetch(PullRequestDiff diff, Set<String> changedSymbols);
}
