package com.repoguard.agent.dto;

import java.math.BigDecimal;

public class DashboardLlmQualityModelStat {

    private String modelLabel;
    private Long taskCount;
    private BigDecimal averageDurationMs;
    private BigDecimal averageTokens;
    private BigDecimal averageCost;
    private Long parseSuccessCount;
    private Long fallbackCount;
    private Long partialFallbackCount;
    private Long reviewedFeedbackCount;
    private Long validFeedbackCount;
    private Long falsePositiveFeedbackCount;

    public String getModelLabel() {
        return modelLabel;
    }

    public void setModelLabel(String modelLabel) {
        this.modelLabel = modelLabel;
    }

    public Long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(Long taskCount) {
        this.taskCount = taskCount;
    }

    public BigDecimal getAverageDurationMs() {
        return averageDurationMs;
    }

    public void setAverageDurationMs(BigDecimal averageDurationMs) {
        this.averageDurationMs = averageDurationMs;
    }

    public BigDecimal getAverageTokens() {
        return averageTokens;
    }

    public void setAverageTokens(BigDecimal averageTokens) {
        this.averageTokens = averageTokens;
    }

    public BigDecimal getAverageCost() {
        return averageCost;
    }

    public void setAverageCost(BigDecimal averageCost) {
        this.averageCost = averageCost;
    }

    public Long getParseSuccessCount() {
        return parseSuccessCount;
    }

    public void setParseSuccessCount(Long parseSuccessCount) {
        this.parseSuccessCount = parseSuccessCount;
    }

    public Long getFallbackCount() {
        return fallbackCount;
    }

    public void setFallbackCount(Long fallbackCount) {
        this.fallbackCount = fallbackCount;
    }

    public Long getPartialFallbackCount() {
        return partialFallbackCount;
    }

    public void setPartialFallbackCount(Long partialFallbackCount) {
        this.partialFallbackCount = partialFallbackCount;
    }

    public Long getReviewedFeedbackCount() {
        return reviewedFeedbackCount;
    }

    public void setReviewedFeedbackCount(Long reviewedFeedbackCount) {
        this.reviewedFeedbackCount = reviewedFeedbackCount;
    }

    public Long getValidFeedbackCount() {
        return validFeedbackCount;
    }

    public void setValidFeedbackCount(Long validFeedbackCount) {
        this.validFeedbackCount = validFeedbackCount;
    }

    public Long getFalsePositiveFeedbackCount() {
        return falsePositiveFeedbackCount;
    }

    public void setFalsePositiveFeedbackCount(Long falsePositiveFeedbackCount) {
        this.falsePositiveFeedbackCount = falsePositiveFeedbackCount;
    }
}
