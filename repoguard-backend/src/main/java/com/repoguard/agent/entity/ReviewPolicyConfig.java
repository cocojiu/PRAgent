package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_policy_config")
public class ReviewPolicyConfig {

    @TableId
    private Long id;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
