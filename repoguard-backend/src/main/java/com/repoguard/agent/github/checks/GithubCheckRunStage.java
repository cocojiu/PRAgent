package com.repoguard.agent.github.checks;

import java.util.Locale;

/** Ordered lifecycle stages persisted before they are sent to GitHub. */
public enum GithubCheckRunStage {

    QUEUED(1, "queued"),
    IN_PROGRESS(2, "in_progress"),
    COMPLETED(3, "completed");

    private final int rank;
    private final String githubStatus;

    GithubCheckRunStage(int rank, String githubStatus) {
        this.rank = rank;
        this.githubStatus = githubStatus;
    }

    public int rank() {
        return rank;
    }

    public String githubStatus() {
        return githubStatus;
    }

    public static GithubCheckRunStage from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (GithubCheckRunStage stage : values()) {
            if (stage.name().equals(normalized)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown GitHub Check Run stage: " + value);
    }
}
