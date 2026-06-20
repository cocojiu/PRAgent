package com.repoguard.agent.dto;

import java.math.BigDecimal;

public class DashboardMetricStat {

    private Long total;
    private Long highRisk;
    private Long failed;
    private BigDecimal averageDurationSeconds;

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getHighRisk() {
        return highRisk;
    }

    public void setHighRisk(Long highRisk) {
        this.highRisk = highRisk;
    }

    public Long getFailed() {
        return failed;
    }

    public void setFailed(Long failed) {
        this.failed = failed;
    }

    public BigDecimal getAverageDurationSeconds() {
        return averageDurationSeconds;
    }

    public void setAverageDurationSeconds(BigDecimal averageDurationSeconds) {
        this.averageDurationSeconds = averageDurationSeconds;
    }
}
