package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardMetricStat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DashboardMetricAssembler {

    private final DashboardOverviewDisplayMapper overviewDisplayMapper;

    public DashboardMetricAssembler(DashboardOverviewDisplayMapper overviewDisplayMapper) {
        this.overviewDisplayMapper =
            Objects.requireNonNull(overviewDisplayMapper, "overviewDisplayMapper must not be null");
    }

    public List<DashboardMetricDto> assemble(DashboardMetricStat stat) {
        long total = stat == null ? 0L : safeCount(stat.getTotal());
        long highRisk = stat == null ? 0L : safeCount(stat.getHighRisk());
        long failed = stat == null ? 0L : safeCount(stat.getFailed());
        Integer averageDurationSeconds = stat == null ? null : safeAverageDuration(stat.getAverageDurationSeconds());

        DashboardOverviewDisplayMapper.MetricDisplay totalReviews = overviewDisplayMapper.totalReviewsMetric();
        DashboardOverviewDisplayMapper.MetricDisplay highRiskPullRequests = overviewDisplayMapper.highRiskPullRequestsMetric();
        DashboardOverviewDisplayMapper.MetricDisplay failedTasks = overviewDisplayMapper.failedTasksMetric();
        DashboardOverviewDisplayMapper.MetricDisplay averageReviewDuration = overviewDisplayMapper.averageReviewDurationMetric();

        return List.of(
            metric(totalReviews, String.valueOf(total), "0.0%"),
            metric(highRiskPullRequests, String.valueOf(highRisk), percent(highRisk, total)),
            metric(failedTasks, String.valueOf(failed), percent(failed, total)),
            metric(averageReviewDuration, formatDuration(averageDurationSeconds), "0.0%")
        );
    }

    private DashboardMetricDto metric(DashboardOverviewDisplayMapper.MetricDisplay display, String value, String trend) {
        return new DashboardMetricDto(display.label(), value, trend, display.trendType(), display.color());
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private Integer safeAverageDuration(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private String formatDuration(Integer durationSeconds) {
        if (durationSeconds == null) {
            return "—";
        }
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "\u5206" + seconds + "\u79d2";
    }
}
