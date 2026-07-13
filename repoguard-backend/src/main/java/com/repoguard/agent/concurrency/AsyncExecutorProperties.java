package com.repoguard.agent.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.async")
public class AsyncExecutorProperties {

    private int githubCommentThreads = 2;
    private int githubCommentQueueCapacity = 100;
    private int reviewPublishThreads = 2;
    private int reviewPublishQueueCapacity = 100;
    private int dashboardThreads = 1;
    private int dashboardQueueCapacity = 16;
    private int rabbitHealthThreads = 1;
    private int rabbitHealthQueueCapacity = 4;
    private int shutdownWaitSeconds = 10;
    private long rabbitHealthCacheMillis = 5_000;
    private long rabbitHealthFailureBackoffMillis = 10_000;

    public int getGithubCommentThreads() { return githubCommentThreads; }
    public void setGithubCommentThreads(int value) { githubCommentThreads = value; }
    public int getGithubCommentQueueCapacity() { return githubCommentQueueCapacity; }
    public void setGithubCommentQueueCapacity(int value) { githubCommentQueueCapacity = value; }
    public int getReviewPublishThreads() { return reviewPublishThreads; }
    public void setReviewPublishThreads(int value) { reviewPublishThreads = value; }
    public int getReviewPublishQueueCapacity() { return reviewPublishQueueCapacity; }
    public void setReviewPublishQueueCapacity(int value) { reviewPublishQueueCapacity = value; }
    public int getDashboardThreads() { return dashboardThreads; }
    public void setDashboardThreads(int value) { dashboardThreads = value; }
    public int getDashboardQueueCapacity() { return dashboardQueueCapacity; }
    public void setDashboardQueueCapacity(int value) { dashboardQueueCapacity = value; }
    public int getRabbitHealthThreads() { return rabbitHealthThreads; }
    public void setRabbitHealthThreads(int value) { rabbitHealthThreads = value; }
    public int getRabbitHealthQueueCapacity() { return rabbitHealthQueueCapacity; }
    public void setRabbitHealthQueueCapacity(int value) { rabbitHealthQueueCapacity = value; }
    public int getShutdownWaitSeconds() { return shutdownWaitSeconds; }
    public void setShutdownWaitSeconds(int value) { shutdownWaitSeconds = value; }
    public long getRabbitHealthCacheMillis() { return rabbitHealthCacheMillis; }
    public void setRabbitHealthCacheMillis(long value) { rabbitHealthCacheMillis = value; }
    public long getRabbitHealthFailureBackoffMillis() { return rabbitHealthFailureBackoffMillis; }
    public void setRabbitHealthFailureBackoffMillis(long value) { rabbitHealthFailureBackoffMillis = value; }

    public int positive(int value) { return Math.max(1, value); }
}
