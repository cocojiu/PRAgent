package com.repoguard.agent.service.impl;

import org.springframework.stereotype.Component;

@Component
public class DashboardOverviewDisplayMapper {

    public MetricDisplay totalReviewsMetric() {
        return new MetricDisplay("\u672c\u5468\u5ba1\u67e5", "up", "blue");
    }

    public MetricDisplay highRiskPullRequestsMetric() {
        return new MetricDisplay("\u9ad8\u98ce\u9669 PR", "up-danger", "red");
    }

    public MetricDisplay failedTasksMetric() {
        return new MetricDisplay("\u5931\u8d25\u4efb\u52a1", "down", "orange");
    }

    public MetricDisplay averageReviewDurationMetric() {
        return new MetricDisplay("\u5e73\u5747\u5ba1\u67e5\u8017\u65f6", "down", "green");
    }

    public RiskLevelDisplay riskLevel(String riskLevel) {
        return switch (riskLevel) {
            case "HIGH" -> new RiskLevelDisplay("\u9ad8\u98ce\u9669", "#ef4444");
            case "MEDIUM" -> new RiskLevelDisplay("\u4e2d\u98ce\u9669", "#f59e0b");
            case "LOW" -> new RiskLevelDisplay("\u4f4e\u98ce\u9669", "#2563eb");
            case "INFO" -> new RiskLevelDisplay("\u63d0\u793a", "#22c55e");
            default -> new RiskLevelDisplay(riskLevel, "#14b8a6");
        };
    }

    record MetricDisplay(String label, String trendType, String color) {
    }

    record RiskLevelDisplay(String name, String color) {
    }
}
