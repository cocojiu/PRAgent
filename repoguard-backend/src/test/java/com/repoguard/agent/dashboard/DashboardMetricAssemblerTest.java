package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardMetricStat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardMetricAssemblerTest {

    private final DashboardMetricAssembler assembler = new DashboardMetricAssembler(
        new DashboardOverviewDisplayMapper()
    );

    @Test
    void assemblesTopMetricsFromAggregateStat() {
        List<DashboardMetricDto> metrics = assembler.assemble(metricStat(3L, 2L, 1L, BigDecimal.valueOf(1800)));

        assertThat(metrics).extracting(DashboardMetricDto::label)
            .containsExactly("\u672c\u5468\u5ba1\u67e5", "\u9ad8\u98ce\u9669 PR", "\u5931\u8d25\u4efb\u52a1", "\u5e73\u5747\u5ba1\u67e5\u8017\u65f6");
        assertThat(metrics).extracting(DashboardMetricDto::value)
            .containsExactly("3", "2", "1", "30\u52060\u79d2");
        assertThat(metrics).extracting(DashboardMetricDto::trend)
            .containsExactly("0.0%", "66.7%", "33.3%", "0.0%");
        assertThat(metrics).extracting(DashboardMetricDto::trendType)
            .containsExactly("up", "up-danger", "down", "down");
        assertThat(metrics).extracting(DashboardMetricDto::color)
            .containsExactly("blue", "red", "orange", "green");
    }

    @Test
    void nullStatKeepsCountsButMarksAverageDurationUnknown() {
        List<DashboardMetricDto> metrics = assembler.assemble(null);

        assertThat(metrics).extracting(DashboardMetricDto::value)
            .containsExactly("0", "0", "0", "—");
        assertThat(metrics).extracting(DashboardMetricDto::trend)
            .containsExactly("0.0%", "0.0%", "0.0%", "0.0%");
    }

    @Test
    void roundsAverageDurationSecondsBeforeFormatting() {
        List<DashboardMetricDto> metrics = assembler.assemble(metricStat(1L, 0L, 0L, BigDecimal.valueOf(62.6)));

        assertThat(metrics.get(3).value()).isEqualTo("1\u52063\u79d2");
    }

    private DashboardMetricStat metricStat(Long total, Long highRisk, Long failed, BigDecimal averageDurationSeconds) {
        DashboardMetricStat stat = new DashboardMetricStat();
        stat.setTotal(total);
        stat.setHighRisk(highRisk);
        stat.setFailed(failed);
        stat.setAverageDurationSeconds(averageDurationSeconds);
        return stat;
    }
}
