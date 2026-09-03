package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_policy_config")
public class ReviewPolicyConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Boolean llmEnabled;
    private String llmProvider;
    private String modelName;
    private String baseUrl;
    private String apiKeyValue;
    private Integer timeoutSeconds;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Boolean fallbackToRules;
    private Integer workerConcurrency;
    private Integer chunkFileThreshold;
    private Integer chunkLineThreshold;
    private Integer chunkMaxFiles;
    private Integer chunkMaxLines;
    private BigDecimal inputTokenPricePerMillion;
    private BigDecimal outputTokenPricePerMillion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Boolean getLlmEnabled() {
        return llmEnabled;
    }

    public void setLlmEnabled(Boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyValue() {
        return apiKeyValue;
    }

    public void setApiKeyValue(String apiKeyValue) {
        this.apiKeyValue = apiKeyValue;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getFallbackToRules() {
        return fallbackToRules;
    }

    public void setFallbackToRules(Boolean fallbackToRules) {
        this.fallbackToRules = fallbackToRules;
    }

    public Integer getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(Integer workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public Integer getChunkFileThreshold() {
        return chunkFileThreshold;
    }

    public void setChunkFileThreshold(Integer chunkFileThreshold) {
        this.chunkFileThreshold = chunkFileThreshold;
    }

    public Integer getChunkLineThreshold() {
        return chunkLineThreshold;
    }

    public void setChunkLineThreshold(Integer chunkLineThreshold) {
        this.chunkLineThreshold = chunkLineThreshold;
    }

    public Integer getChunkMaxFiles() {
        return chunkMaxFiles;
    }

    public void setChunkMaxFiles(Integer chunkMaxFiles) {
        this.chunkMaxFiles = chunkMaxFiles;
    }

    public Integer getChunkMaxLines() {
        return chunkMaxLines;
    }

    public void setChunkMaxLines(Integer chunkMaxLines) {
        this.chunkMaxLines = chunkMaxLines;
    }

    public BigDecimal getInputTokenPricePerMillion() {
        return inputTokenPricePerMillion;
    }

    public void setInputTokenPricePerMillion(BigDecimal inputTokenPricePerMillion) {
        this.inputTokenPricePerMillion = inputTokenPricePerMillion;
    }

    public BigDecimal getOutputTokenPricePerMillion() {
        return outputTokenPricePerMillion;
    }

    public void setOutputTokenPricePerMillion(BigDecimal outputTokenPricePerMillion) {
        this.outputTokenPricePerMillion = outputTokenPricePerMillion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
