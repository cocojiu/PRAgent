package com.repoguard.agent.worker;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionDiffStats {

    int fileCount(GithubPullRequestDiff diff) {
        return diff.files() == null ? 0 : diff.files().size();
    }

    int totalAdditions(GithubPullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(GithubChangedFile::additions)
            .mapToInt(this::safeInt)
            .sum();
    }

    int totalDeletions(GithubPullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(GithubChangedFile::deletions)
            .mapToInt(this::safeInt)
            .sum();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
