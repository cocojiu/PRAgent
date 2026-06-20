package com.repoguard.agent.dto;

public class DashboardLlmQualityTrendCount {

    private String dayKey;
    private Long taskCount;
    private Long parseSuccessCount;
    private Long fallbackCount;
    private Long partialFallbackCount;

    public String getDayKey() {
        return dayKey;
    }

    public void setDayKey(String dayKey) {
        this.dayKey = dayKey;
    }

    public Long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(Long taskCount) {
        this.taskCount = taskCount;
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
}
