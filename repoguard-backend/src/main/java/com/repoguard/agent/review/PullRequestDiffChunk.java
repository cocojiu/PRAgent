package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;

public record PullRequestDiffChunk(
    Integer index,
    Integer total,
    GithubPullRequestDiff diff,
    Integer fileCount,
    Integer additions,
    Integer deletions,
    List<String> reasons
) {
}
