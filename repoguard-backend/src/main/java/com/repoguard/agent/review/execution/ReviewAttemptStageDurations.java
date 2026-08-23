package com.repoguard.agent.review.execution;

import java.time.Duration;

public final class ReviewAttemptStageDurations {

    private long diffFetchMs;
    private long reviewMs;
    private long persistMs;

    public void add(String stage, Duration duration) {
        long millis = Math.max(0L, duration == null ? 0L : duration.toMillis());
        switch (stage) {
            case "diff_fetch" -> diffFetchMs = saturatedAdd(diffFetchMs, millis);
            case "review" -> reviewMs = saturatedAdd(reviewMs, millis);
            case "db_write", "claim" -> persistMs = saturatedAdd(persistMs, millis);
            default -> {
                // Unknown stages are still emitted as metrics, but are not part
                // of the stable Attempt persistence contract.
            }
        }
    }

    public long diffFetchMs() { return diffFetchMs; }
    public long reviewMs() { return reviewMs; }
    public long persistMs() { return persistMs; }

    private long saturatedAdd(long current, long additional) {
        return Long.MAX_VALUE - current < additional ? Long.MAX_VALUE : current + additional;
    }
}
