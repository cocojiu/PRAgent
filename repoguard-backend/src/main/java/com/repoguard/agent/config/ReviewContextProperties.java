package com.repoguard.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.review.changed-file-context")
public class ReviewContextProperties {

    @Min(1)
    @Max(500)
    private int maxFiles = 100;

    @Min(16_384)
    @Max(8_388_608)
    private int maxFileBytes = 524_288;

    @Min(65_536)
    @Max(67_108_864)
    private int maxTotalBytes = 8_388_608;

    @Min(100)
    @Max(120_000)
    private long totalTimeoutMs = 15_000;

    @Min(16)
    @Max(20_000)
    private long cacheMaximumSize = 2_000;

    @Min(1_048_576)
    @Max(1_073_741_824)
    private long cacheMaximumBytes = 67_108_864;

    @Min(10)
    @Max(86_400)
    private long cacheTtlSeconds = 600;

    private List<String> excludedPathPatterns = new ArrayList<>(List.of(
        "**/generated/**",
        "**/generated-sources/**",
        "**/vendor/**",
        "**/build/**",
        "**/target/**",
        "**/dist/**",
        "**/node_modules/**",
        ".env",
        ".env.*",
        "*.pem",
        "*.key",
        "*.p12",
        "*.pfx",
        "*.jks",
        "*.keystore",
        "credentials",
        "credentials.json",
        "service-account.json",
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519"
    ));

    private List<String> nonProductionPathPatterns = new ArrayList<>(List.of(
        "^src/test/**",
        "**/src/test/**",
        "^src/it/**",
        "**/src/it/**",
        "^test/**",
        "^tests/**",
        "**/fixtures/**",
        "**/__fixtures__/**",
        "^demo/**",
        "^example/**",
        "^examples/**",
        "^samples/**",
        "**/src/main/resources/**/demo/**",
        "**/src/main/resources/**/examples/**",
        "^docs/**"
    ));

    private List<String> approvedMessagePublisherPatterns = new ArrayList<>(List.of(
        "**/com/repoguard/agent/messaging/ReviewTaskPublisher.java",
        "**/com/repoguard/agent/notification/delivery/NotificationEventPublisher.java",
        "**/messaging/ConfirmedPublisher.java",
        "**/outbox/**"
    ));

    private List<String> approvedGithubPublisherPatterns = new ArrayList<>(List.of(
        "**/com/repoguard/agent/github/comment/GithubReviewBatchPublisher.java",
        "**/com/repoguard/agent/github/**/GithubLineCommentFallbackPublisher.java",
        "**/com/repoguard/agent/github/**/GithubSupersededSummaryPublisher.java",
        "**/com/repoguard/agent/github/GithubCommentWriter.java",
        "**/com/repoguard/agent/github/publication/**"
    ));

    private List<String> approvedAuthorizationBoundaryPatterns = new ArrayList<>(List.of(
        "**/security/gateway/**",
        "**/security/secured/**"
    ));

    private List<String> approvedRedactionMethods = new ArrayList<>(List.of(
        "mask",
        "masked",
        "redact",
        "sanitize",
        "hash",
        "fingerprint",
        "summary"
    ));

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(int maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(int maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(long totalTimeoutMs) {
        this.totalTimeoutMs = totalTimeoutMs;
    }

    public long getCacheMaximumSize() {
        return cacheMaximumSize;
    }

    public void setCacheMaximumSize(long cacheMaximumSize) {
        this.cacheMaximumSize = cacheMaximumSize;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public long getCacheMaximumBytes() {
        return cacheMaximumBytes;
    }

    public void setCacheMaximumBytes(long cacheMaximumBytes) {
        this.cacheMaximumBytes = cacheMaximumBytes;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public List<String> getExcludedPathPatterns() {
        return excludedPathPatterns;
    }

    public void setExcludedPathPatterns(List<String> excludedPathPatterns) {
        this.excludedPathPatterns = mutableCopy(excludedPathPatterns);
    }

    public List<String> getNonProductionPathPatterns() {
        return nonProductionPathPatterns;
    }

    public void setNonProductionPathPatterns(List<String> nonProductionPathPatterns) {
        this.nonProductionPathPatterns = mutableCopy(nonProductionPathPatterns);
    }

    public List<String> getApprovedMessagePublisherPatterns() {
        return approvedMessagePublisherPatterns;
    }

    public void setApprovedMessagePublisherPatterns(List<String> approvedMessagePublisherPatterns) {
        this.approvedMessagePublisherPatterns = mutableCopy(approvedMessagePublisherPatterns);
    }

    public List<String> getApprovedGithubPublisherPatterns() {
        return approvedGithubPublisherPatterns;
    }

    public void setApprovedGithubPublisherPatterns(List<String> approvedGithubPublisherPatterns) {
        this.approvedGithubPublisherPatterns = mutableCopy(approvedGithubPublisherPatterns);
    }

    public List<String> getApprovedAuthorizationBoundaryPatterns() {
        return approvedAuthorizationBoundaryPatterns;
    }

    public void setApprovedAuthorizationBoundaryPatterns(List<String> approvedAuthorizationBoundaryPatterns) {
        this.approvedAuthorizationBoundaryPatterns = mutableCopy(approvedAuthorizationBoundaryPatterns);
    }

    public List<String> getApprovedRedactionMethods() {
        return approvedRedactionMethods;
    }

    public void setApprovedRedactionMethods(List<String> approvedRedactionMethods) {
        this.approvedRedactionMethods = mutableCopy(approvedRedactionMethods);
    }

    private List<String> mutableCopy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
