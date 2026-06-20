package com.repoguard.agent.dto;

public class DashboardLlmQualityRepositoryStat {

    private String repositoryLabel;
    private Long taskCount;
    private Long fallbackCount;
    private Long partialFallbackCount;
    private Long reviewedFeedbackCount;
    private Long validFeedbackCount;
    private Long falsePositiveFeedbackCount;

    public String getRepositoryLabel() {
        return repositoryLabel;
    }

    public void setRepositoryLabel(String repositoryLabel) {
        this.repositoryLabel = repositoryLabel;
    }

    public Long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(Long taskCount) {
        this.taskCount = taskCount;
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
