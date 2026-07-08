package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.mapper.DashboardDailySnapshotMapper;
import com.repoguard.agent.mapper.DashboardMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardDailySnapshotServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:00:00Z"), ZoneId.of("UTC"));

    private final DashboardDailySnapshotMapper snapshotMapper =
        org.mockito.Mockito.mock(DashboardDailySnapshotMapper.class);
    private final DashboardMapper dashboardMapper = org.mockito.Mockito.mock(DashboardMapper.class);
    private final DashboardDailySnapshotService service = new DashboardDailySnapshotService(
        snapshotMapper,
        dashboardMapper,
        DashboardReviewTrendWindow.forTest(FIXED_CLOCK),
        DashboardLlmQualityTrendBuilder.forTest(new DashboardLlmQualityFormatter(), FIXED_CLOCK)
    );

    @Test
    void metricStatRefreshesReviewAndRuleSnapshotsWhenWindowIsMissing() {
        LocalDate startDate = LocalDate.of(2026, 6, 13);
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectMetricStat(startDate)).thenReturn(metricStat(3L));

        DashboardMetricStat stat = service.selectMetricStat(startDate);

        verify(snapshotMapper).deleteReviewDailyStatsFrom(startDate);
        verify(snapshotMapper).insertReviewDailyStatsFromTasks(startDate);
        verify(snapshotMapper).deleteRuleDailyStatsFrom(startDate);
        verify(snapshotMapper).insertRuleDailyStatsFromFindings(startDate);
        assertThat(stat.getTotal()).isEqualTo(3L);
    }

    @Test
    void riskDistributionReadsSnapshotDirectlyWhenWindowIsCovered() {
        LocalDate startDate = LocalDate.of(2026, 6, 13);
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectEarliestReviewSnapshotDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(snapshotMapper.selectLatestReviewSnapshotDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectRiskLevelCounts(startDate)).thenReturn(List.of(riskLevelCount("HIGH", 2L)));

        List<DashboardRiskLevelCount> counts = service.selectRiskLevelCounts(startDate);

        verify(snapshotMapper, never()).deleteReviewDailyStatsFrom(startDate);
        verify(snapshotMapper, never()).insertReviewDailyStatsFromTasks(startDate);
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).getTotal()).isEqualTo(2L);
    }

    @Test
    void llmStatsRefreshWhenRequestedWindowStartsBeforeExistingSnapshot() {
        LocalDate startDate = LocalDate.of(2026, 3, 22);
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectEarliestLlmQualitySnapshotDate()).thenReturn(LocalDate.of(2026, 6, 13));
        when(snapshotMapper.selectLatestLlmQualitySnapshotDate()).thenReturn(LocalDate.of(2026, 6, 19));

        service.selectLlmQualityTrendCounts(startDate);

        verify(snapshotMapper).deleteLlmQualityDailyStatsFrom(startDate);
        verify(snapshotMapper).insertLlmQualityDailyStatsFromTasks(startDate);
    }

    @Test
    void refreshCurrentWindowsRebuildsReviewAndNinetyDayLlmSnapshots() {
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));

        service.refreshCurrentWindows();

        verify(snapshotMapper).deleteReviewDailyStatsFrom(LocalDate.of(2026, 6, 13));
        verify(snapshotMapper).insertReviewDailyStatsFromTasks(LocalDate.of(2026, 6, 13));
        verify(snapshotMapper).deleteRuleDailyStatsFrom(LocalDate.of(2026, 6, 13));
        verify(snapshotMapper).insertRuleDailyStatsFromFindings(LocalDate.of(2026, 6, 13));
        verify(snapshotMapper).deleteLlmQualityDailyStatsFrom(LocalDate.of(2026, 3, 22));
        verify(snapshotMapper).insertLlmQualityDailyStatsFromTasks(LocalDate.of(2026, 3, 22));
    }

    private DashboardMetricStat metricStat(Long total) {
        DashboardMetricStat stat = new DashboardMetricStat();
        stat.setTotal(total);
        stat.setHighRisk(1L);
        stat.setFailed(0L);
        stat.setAverageDurationSeconds(BigDecimal.TEN);
        return stat;
    }

    private DashboardRiskLevelCount riskLevelCount(String riskLevel, Long total) {
        DashboardRiskLevelCount count = new DashboardRiskLevelCount();
        count.setRiskLevel(riskLevel);
        count.setTotal(total);
        return count;
    }
}
