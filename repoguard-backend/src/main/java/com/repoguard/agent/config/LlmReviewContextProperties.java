package com.repoguard.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.llm-context")
public class LlmReviewContextProperties {

    @Min(4_096)
    @Max(100_000)
    private int maxTotalChars = 24_000;

    @Min(512)
    @Max(20_000)
    private int maxSliceChars = 6_000;

    @Min(0)
    @Max(50)
    private int maxRelatedFiles = 8;

    @Min(1)
    @Max(100)
    private int maxRulePolicies = 20;

    @Min(80)
    @Max(2_000)
    private int maxRuleTextChars = 360;

    private boolean semanticIndexEnabled = true;

    @Min(1)
    @Max(200)
    private int semanticIndexMaxFiles = 24;

    @Min(4_096)
    @Max(524_288)
    private int semanticIndexMaxFileBytes = 65_536;

    @Min(16_384)
    @Max(4_194_304)
    private int semanticIndexMaxTotalBytes = 1_048_576;

    @Min(100)
    @Max(15_000)
    private long semanticIndexTimeoutMs = 3_000;

    @Min(1)
    @Max(500)
    private long semanticIndexCacheMaximumSize = 128;

    @Min(10)
    @Max(86_400)
    private long semanticIndexCacheTtlSeconds = 900;

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = maxTotalChars;
    }

    public int getMaxSliceChars() {
        return maxSliceChars;
    }

    public void setMaxSliceChars(int maxSliceChars) {
        this.maxSliceChars = maxSliceChars;
    }

    public int getMaxRelatedFiles() {
        return maxRelatedFiles;
    }

    public void setMaxRelatedFiles(int maxRelatedFiles) {
        this.maxRelatedFiles = maxRelatedFiles;
    }

    public int getMaxRulePolicies() {
        return maxRulePolicies;
    }

    public void setMaxRulePolicies(int maxRulePolicies) {
        this.maxRulePolicies = maxRulePolicies;
    }

    public int getMaxRuleTextChars() {
        return maxRuleTextChars;
    }

    public void setMaxRuleTextChars(int maxRuleTextChars) {
        this.maxRuleTextChars = maxRuleTextChars;
    }

    public boolean isSemanticIndexEnabled() {
        return semanticIndexEnabled;
    }

    public void setSemanticIndexEnabled(boolean semanticIndexEnabled) {
        this.semanticIndexEnabled = semanticIndexEnabled;
    }

    public int getSemanticIndexMaxFiles() {
        return semanticIndexMaxFiles;
    }

    public void setSemanticIndexMaxFiles(int semanticIndexMaxFiles) {
        this.semanticIndexMaxFiles = semanticIndexMaxFiles;
    }

    public int getSemanticIndexMaxFileBytes() {
        return semanticIndexMaxFileBytes;
    }

    public void setSemanticIndexMaxFileBytes(int semanticIndexMaxFileBytes) {
        this.semanticIndexMaxFileBytes = semanticIndexMaxFileBytes;
    }

    public int getSemanticIndexMaxTotalBytes() {
        return semanticIndexMaxTotalBytes;
    }

    public void setSemanticIndexMaxTotalBytes(int semanticIndexMaxTotalBytes) {
        this.semanticIndexMaxTotalBytes = semanticIndexMaxTotalBytes;
    }

    public long getSemanticIndexTimeoutMs() {
        return semanticIndexTimeoutMs;
    }

    public void setSemanticIndexTimeoutMs(long semanticIndexTimeoutMs) {
        this.semanticIndexTimeoutMs = semanticIndexTimeoutMs;
    }

    public long getSemanticIndexCacheMaximumSize() {
        return semanticIndexCacheMaximumSize;
    }

    public void setSemanticIndexCacheMaximumSize(long semanticIndexCacheMaximumSize) {
        this.semanticIndexCacheMaximumSize = semanticIndexCacheMaximumSize;
    }

    public long getSemanticIndexCacheTtlSeconds() {
        return semanticIndexCacheTtlSeconds;
    }

    public void setSemanticIndexCacheTtlSeconds(long semanticIndexCacheTtlSeconds) {
        this.semanticIndexCacheTtlSeconds = semanticIndexCacheTtlSeconds;
    }
}
