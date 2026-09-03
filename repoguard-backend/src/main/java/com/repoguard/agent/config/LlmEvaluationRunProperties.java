package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for local evaluation input. The root must be provisioned outside the repository. */
@Component
@ConfigurationProperties(prefix = "repoguard.evaluation")
public class LlmEvaluationRunProperties {

    private String dataRoot = "";
    private int executorThreads = 1;
    private int queueCapacity = 2;
    private long maxSampleBytes = 10L * 1024L * 1024L;
    private long maxDatasetBytes = 512L * 1024L * 1024L;

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }

    public int getExecutorThreads() {
        return executorThreads;
    }

    public void setExecutorThreads(int executorThreads) {
        this.executorThreads = executorThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getMaxSampleBytes() {
        return maxSampleBytes;
    }

    public void setMaxSampleBytes(long maxSampleBytes) {
        this.maxSampleBytes = maxSampleBytes;
    }

    public long getMaxDatasetBytes() {
        return maxDatasetBytes;
    }

    public void setMaxDatasetBytes(long maxDatasetBytes) {
        this.maxDatasetBytes = maxDatasetBytes;
    }
}
