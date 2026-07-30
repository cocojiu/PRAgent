package com.repoguard.agent.review;

import java.util.List;

public record PullRequestDiffChunk(
    Integer index,
    Integer total,
    PullRequestDiff diff,
    Integer fileCount,
    Integer additions,
    Integer deletions,
    List<String> reasons
) {
}
