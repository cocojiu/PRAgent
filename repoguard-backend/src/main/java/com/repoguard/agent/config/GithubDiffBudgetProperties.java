package com.repoguard.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "app.github.diff-budget")
public class GithubDiffBudgetProperties {

    @Min(1)
    @Max(100)
    private int maxPages = 10;

    @Min(1)
    @Max(3_000)
    private int maxFiles = 1_000;

    @Min(1_048_576)
    @Max(67_108_864)
    private int maxTotalBytes = 33_554_432;

    @Min(65_536)
    @Max(4_194_304)
    private int maxPatchBytes = 524_288;

    @Min(1_000)
    @Max(300_000)
    private long totalTimeoutMs = 90_000;

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(int maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public int getMaxPatchBytes() {
        return maxPatchBytes;
    }

    public void setMaxPatchBytes(int maxPatchBytes) {
        this.maxPatchBytes = maxPatchBytes;
    }

    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(long totalTimeoutMs) {
        this.totalTimeoutMs = totalTimeoutMs;
    }
}
