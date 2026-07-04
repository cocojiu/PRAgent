package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DashboardOverviewDisplayMapperTest {

    private final DashboardOverviewDisplayMapper mapper = new DashboardOverviewDisplayMapper();

    @Test
    void mapsMetricCardsToDisplayMetadata() {
        assertThat(mapper.totalReviewsMetric())
            .extracting("label", "trendType", "color")
            .containsExactly("\u672c\u5468\u5ba1\u67e5", "up", "blue");
        assertThat(mapper.highRiskPullRequestsMetric())
            .extracting("label", "trendType", "color")
            .containsExactly("\u9ad8\u98ce\u9669 PR", "up-danger", "red");
        assertThat(mapper.failedTasksMetric())
            .extracting("label", "trendType", "color")
            .containsExactly("\u5931\u8d25\u4efb\u52a1", "down", "orange");
        assertThat(mapper.averageReviewDurationMetric())
            .extracting("label", "trendType", "color")
            .containsExactly("\u5e73\u5747\u5ba1\u67e5\u8017\u65f6", "down", "green");
    }

    @Test
    void mapsRiskLevelsToDisplayMetadata() {
        assertThat(mapper.riskLevel("HIGH"))
            .extracting("name", "color")
            .containsExactly("\u9ad8\u98ce\u9669", "#ef4444");
        assertThat(mapper.riskLevel("MEDIUM"))
            .extracting("name", "color")
            .containsExactly("\u4e2d\u98ce\u9669", "#f59e0b");
        assertThat(mapper.riskLevel("LOW"))
            .extracting("name", "color")
            .containsExactly("\u4f4e\u98ce\u9669", "#2563eb");
        assertThat(mapper.riskLevel("INFO"))
            .extracting("name", "color")
            .containsExactly("\u63d0\u793a", "#22c55e");
    }

    @Test
    void keepsUnknownRiskLevelWithDefaultColor() {
        assertThat(mapper.riskLevel("CUSTOM"))
            .extracting("name", "color")
            .containsExactly("CUSTOM", "#14b8a6");
    }
}
