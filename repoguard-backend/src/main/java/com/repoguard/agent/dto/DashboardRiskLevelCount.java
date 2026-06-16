package com.repoguard.agent.dto;

public class DashboardRiskLevelCount {

    private String riskLevel;
    private Long total;

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
