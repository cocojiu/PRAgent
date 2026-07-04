package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;

record SemanticDiffSegment(
    GithubChangedFile file,
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
