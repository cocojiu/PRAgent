package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;

record DiffChunkingPolicy(
    int maxFilesPerChunk,
    int maxLinesPerChunk,
    int largePrFileThreshold,
    int largePrLineThreshold
) {

    private static final int MAX_FILES_PER_CHUNK = 4;
    private static final int MAX_LINES_PER_CHUNK = 450;
    private static final int LARGE_PR_FILE_THRESHOLD = 6;
    private static final int LARGE_PR_LINE_THRESHOLD = 700;

    static DiffChunkingPolicy defaults() {
        return new DiffChunkingPolicy(
            MAX_FILES_PER_CHUNK,
            MAX_LINES_PER_CHUNK,
            LARGE_PR_FILE_THRESHOLD,
            LARGE_PR_LINE_THRESHOLD
        );
    }

    static DiffChunkingPolicy from(ReviewPolicyConfig config) {
        if (config == null) {
            return defaults();
        }
        return new DiffChunkingPolicy(
            positive(config.getChunkMaxFiles(), MAX_FILES_PER_CHUNK),
            positive(config.getChunkMaxLines(), MAX_LINES_PER_CHUNK),
            positive(config.getChunkFileThreshold(), LARGE_PR_FILE_THRESHOLD),
            positive(config.getChunkLineThreshold(), LARGE_PR_LINE_THRESHOLD)
        );
    }

    static DiffChunkingPolicy from(ReviewPolicySettings settings) {
        if (settings == null) {
            return defaults();
        }
        return new DiffChunkingPolicy(
            positive(settings.chunkMaxFiles(), MAX_FILES_PER_CHUNK),
            positive(settings.chunkMaxLines(), MAX_LINES_PER_CHUNK),
            positive(settings.chunkFileThreshold(), LARGE_PR_FILE_THRESHOLD),
            positive(settings.chunkLineThreshold(), LARGE_PR_LINE_THRESHOLD)
        );
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}
