package com.repoguard.agent.worker;

import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionDiffStats {

    int fileCount(PullRequestDiff diff) {
        return diff.files() == null ? 0 : diff.files().size();
    }

    int totalAdditions(PullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(PullRequestChangedFile::additions)
            .mapToInt(this::safeInt)
            .sum();
    }

    int totalDeletions(PullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(PullRequestChangedFile::deletions)
            .mapToInt(this::safeInt)
            .sum();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
