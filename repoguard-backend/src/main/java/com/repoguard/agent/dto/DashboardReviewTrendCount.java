package com.repoguard.agent.dto;

public class DashboardReviewTrendCount {

    private String dayLabel;
    private Long total;

    public String getDayLabel() {
        return dayLabel;
    }

    public void setDayLabel(String dayLabel) {
        this.dayLabel = dayLabel;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
