package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.mapper.DashboardDailySnapshotMapper;
import com.repoguard.agent.mapper.DashboardMapper;
import com.repoguard.agent.mapper.projection.DashboardProjections.MetricStat;
import com.repoguard.agent.mapper.projection.DashboardProjections.RiskLevelCount;
import com.repoguard.agent.mapper.projection.DashboardProjections.SnapshotRefreshState;
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

        verify(snapshotMapper, times(7)).deleteReviewDailyStatsOn(org.mockito.ArgumentMatchers.any());
        verify(snapshotMapper).insertReviewDailyStatsForDate(startDate);
        verify(snapshotMapper).insertReviewDailyStatsForDate(LocalDate.of(2026, 6, 19));
        verify(snapshotMapper, times(7)).deleteRuleDailyStatsOn(org.mockito.ArgumentMatchers.any());
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

        verify(snapshotMapper, never()).deleteReviewDailyStatsOn(startDate);
        verify(snapshotMapper, never()).insertReviewDailyStatsForDate(startDate);
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).getTotal()).isEqualTo(2L);
    }

    @Test
    void llmStatsDoNotRebuildEmptyLeadingDaysWhenLatestSourceIsCovered() {
        LocalDate startDate = LocalDate.of(2026, 3, 22);
        when(snapshotMapper.selectLatestLlmQualitySourceDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectLatestLlmQualitySnapshotDate()).thenReturn(LocalDate.of(2026, 6, 19));

        service.selectLlmQualityTrendCounts(startDate);

        verify(snapshotMapper, never()).deleteLlmQualityDailyStatsOn(startDate);
        verify(snapshotMapper, never()).insertLlmQualityDailyStatsForDate(startDate);
    }

    @Test
    void dirtyDateIsRebuiltAndAcknowledgedWithoutRefreshingTheWholeWindow() {
        LocalDate startDate = LocalDate.of(2026, 6, 13);
        LocalDate dirtyDate = LocalDate.of(2026, 6, 17);
        SnapshotRefreshState dirtyState = new SnapshotRefreshState(dirtyDate, 4L, 3L, 7L, 6L);
        when(snapshotMapper.selectDirtyRefreshStates(startDate, 128)).thenReturn(List.of(dirtyState));
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectLatestReviewSnapshotDate()).thenReturn(LocalDate.of(2026, 6, 19));
        when(snapshotMapper.selectMetricStat(startDate)).thenReturn(metricStat(3L));

        service.selectMetricStat(startDate);

        verify(snapshotMapper).deleteReviewDailyStatsOn(dirtyDate);
        verify(snapshotMapper).insertReviewDailyStatsForDate(dirtyDate);
        verify(snapshotMapper).deleteRuleDailyStatsOn(dirtyDate);
        verify(snapshotMapper).insertRuleDailyStatsForDate(dirtyDate);
        verify(snapshotMapper).markReviewRefreshed(dirtyDate, 4L);
        verify(snapshotMapper).deleteLlmQualityDailyStatsOn(dirtyDate);
        verify(snapshotMapper).insertLlmQualityDailyStatsForDate(dirtyDate);
        verify(snapshotMapper).markLlmQualityRefreshed(dirtyDate, 7L);
        verify(snapshotMapper, times(1)).deleteReviewDailyStatsOn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshCurrentWindowsRebuildsReviewAndNinetyDayLlmSnapshots() {
        when(dashboardMapper.selectLatestReviewTaskDate()).thenReturn(LocalDate.of(2026, 6, 19));

        service.refreshCurrentWindows();

        verify(snapshotMapper, times(7)).deleteReviewDailyStatsOn(org.mockito.ArgumentMatchers.any());
        verify(snapshotMapper).insertReviewDailyStatsForDate(LocalDate.of(2026, 6, 13));
        verify(snapshotMapper).insertReviewDailyStatsForDate(LocalDate.of(2026, 6, 19));
        verify(snapshotMapper, times(90)).deleteLlmQualityDailyStatsOn(org.mockito.ArgumentMatchers.any());
        verify(snapshotMapper).insertLlmQualityDailyStatsForDate(LocalDate.of(2026, 3, 22));
        verify(snapshotMapper).insertLlmQualityDailyStatsForDate(LocalDate.of(2026, 6, 19));
    }

    private MetricStat metricStat(Long total) {
        return new MetricStat(total, 1L, 0L, BigDecimal.TEN);
    }

    private RiskLevelCount riskLevelCount(String riskLevel, Long total) {
        return new RiskLevelCount(riskLevel, total);
    }
}
