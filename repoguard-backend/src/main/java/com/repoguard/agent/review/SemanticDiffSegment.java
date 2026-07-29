package com.repoguard.agent.review;


record SemanticDiffSegment(
    PullRequestChangedFile file,
    String chunkGroupKey,
    String semanticKey,
    String semanticReason,
    int riskPriority,
    int additions,
    int deletions
) {
    int changedLines() {
        return additions + deletions;
    }
}
