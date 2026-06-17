package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public class DashboardHighRiskReview {

    private String title;
    private String repository;
    private String riskLevel;
    private Long ruleHits;
    private LocalDateTime createdAt;
    private String status;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Long getRuleHits() {
        return ruleHits;
    }

    public void setRuleHits(Long ruleHits) {
        this.ruleHits = ruleHits;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
