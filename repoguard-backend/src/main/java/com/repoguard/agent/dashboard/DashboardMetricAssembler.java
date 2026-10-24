package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardMetricStat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    public List<DashboardMetricDto> assemble(DashboardMetricStat stat, LocalDate startDate, LocalDate endDate) {
        long total = stat == null ? 0L : Objects.requireNonNullElse(stat.getTotal(), 0L);
        long highRisk = stat == null ? 0L : Objects.requireNonNullElse(stat.getHighRisk(), 0L);
        long failed = stat == null ? 0L : Objects.requireNonNullElse(stat.getFailed(), 0L);
        Integer averageDurationSeconds = stat == null ? null : safeAverageDuration(stat.getAverageDurationSeconds());

        String period = startDate + " ~ " + endDate;
        return List.of(
            metric(overviewDisplayMapper.totalReviewsMetric(), String.valueOf(total), period),
            metric(overviewDisplayMapper.highRiskPullRequestsMetric(), String.valueOf(highRisk), percent(highRisk, total)),
            metric(overviewDisplayMapper.failedTasksMetric(), String.valueOf(failed), percent(failed, total)),
            metric(overviewDisplayMapper.averageReviewDurationMetric(), formatDuration(averageDurationSeconds), period)
        );
    }

    private DashboardMetricDto metric(DashboardOverviewDisplayMapper.MetricDisplay display, String value, String trend) {
        return new DashboardMetricDto(display.label(), value, trend, display.trendType(), display.color());
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
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
