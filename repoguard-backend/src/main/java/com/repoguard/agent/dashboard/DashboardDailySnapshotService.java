package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.mapper.DashboardDailySnapshotMapper;
import com.repoguard.agent.mapper.DashboardMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class DashboardDailySnapshotService {

    private final DashboardDailySnapshotMapper snapshotMapper;
    private final DashboardMapper dashboardMapper;
    private final DashboardReviewTrendWindow reviewTrendWindow;
    private final DashboardLlmQualityTrendBuilder llmQualityTrendBuilder;

    public DashboardDailySnapshotService(
        DashboardDailySnapshotMapper snapshotMapper,
        DashboardMapper dashboardMapper,
        DashboardReviewTrendWindow reviewTrendWindow,
        DashboardLlmQualityTrendBuilder llmQualityTrendBuilder
    ) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper must not be null");
        this.dashboardMapper = Objects.requireNonNull(dashboardMapper, "dashboardMapper must not be null");
        this.reviewTrendWindow = Objects.requireNonNull(reviewTrendWindow, "reviewTrendWindow must not be null");
        this.llmQualityTrendBuilder =
            Objects.requireNonNull(llmQualityTrendBuilder, "llmQualityTrendBuilder must not be null");
    }

    public LocalDate latestReviewDate() {
        return dashboardMapper.selectLatestReviewTaskDate();
    }

    public DashboardMetricStat selectMetricStat(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return snapshotMapper.selectMetricStat(startDate);
    }

    public List<DashboardReviewTrendCount> selectReviewTrendCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return snapshotMapper.selectReviewTrendCounts(startDate);
    }

    public List<DashboardRiskLevelCount> selectRiskLevelCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return snapshotMapper.selectRiskLevelCounts(startDate);
    }

    public List<DashboardRuleHitCount> selectRuleHitCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return snapshotMapper.selectRuleHitCounts(startDate);
    }

    public List<DashboardLlmQualityTrendCount> selectLlmQualityTrendCounts(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return snapshotMapper.selectLlmQualityTrendCounts(startDate);
    }

    public List<DashboardLlmQualityModelStat> selectLlmQualityByModelStats(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return snapshotMapper.selectLlmQualityByModelStats(startDate);
    }

    public List<DashboardLlmQualityRepositoryStat> selectLlmQualityByRepositoryStats(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return snapshotMapper.selectLlmQualityByRepositoryStats(startDate);
    }

    @Transactional
    public void refreshCurrentWindows() {
        refreshCurrentReviewWindow();
        refreshCurrentLlmQualityWindow();
    }

    @Transactional
    public void refreshCurrentReviewWindow() {
        LocalDate latestReviewDate = latestReviewDate();
        refreshReviewSnapshot(reviewTrendWindow.startDate(latestReviewDate));
    }

    @Transactional
    public void refreshCurrentLlmQualityWindow() {
        LocalDate latestReviewDate = latestReviewDate();
        refreshLlmQualitySnapshot(llmQualityTrendBuilder.window(DashboardLlmTrendDays.NINETY_DAYS, latestReviewDate).startDate());
    }

    @Transactional
    public void refreshReviewSnapshot(LocalDate startDate) {
        LocalDate normalizedStartDate = requireStartDate(startDate);
        snapshotMapper.deleteReviewDailyStatsFrom(normalizedStartDate);
        snapshotMapper.insertReviewDailyStatsFromTasks(normalizedStartDate);
        snapshotMapper.deleteRuleDailyStatsFrom(normalizedStartDate);
        snapshotMapper.insertRuleDailyStatsFromFindings(normalizedStartDate);
    }

    @Transactional
    public void refreshLlmQualitySnapshot(LocalDate startDate) {
        LocalDate normalizedStartDate = requireStartDate(startDate);
        snapshotMapper.deleteLlmQualityDailyStatsFrom(normalizedStartDate);
        snapshotMapper.insertLlmQualityDailyStatsFromTasks(normalizedStartDate);
    }

    private void ensureReviewSnapshot(LocalDate startDate) {
        LocalDate latestReviewDate = latestReviewDate();
        if (latestReviewDate == null) {
            return;
        }
        LocalDate latestSnapshotDate = snapshotMapper.selectLatestReviewSnapshotDate();
        LocalDate earliestSnapshotDate = snapshotMapper.selectEarliestReviewSnapshotDate();
        if (snapshotMissingOrOutsideWindow(startDate, latestReviewDate, earliestSnapshotDate, latestSnapshotDate)) {
            refreshReviewSnapshot(startDate);
        }
    }

    private void ensureLlmQualitySnapshot(LocalDate startDate) {
        LocalDate latestReviewDate = latestReviewDate();
        if (latestReviewDate == null) {
            return;
        }
        LocalDate latestSnapshotDate = snapshotMapper.selectLatestLlmQualitySnapshotDate();
        LocalDate earliestSnapshotDate = snapshotMapper.selectEarliestLlmQualitySnapshotDate();
        if (snapshotMissingOrOutsideWindow(startDate, latestReviewDate, earliestSnapshotDate, latestSnapshotDate)) {
            refreshLlmQualitySnapshot(startDate);
        }
    }

    private boolean snapshotMissingOrOutsideWindow(
        LocalDate startDate,
        LocalDate latestReviewDate,
        LocalDate earliestSnapshotDate,
        LocalDate latestSnapshotDate
    ) {
        LocalDate normalizedStartDate = requireStartDate(startDate);
        return latestSnapshotDate == null
            || latestSnapshotDate.isBefore(latestReviewDate)
            || earliestSnapshotDate == null
            || earliestSnapshotDate.isAfter(normalizedStartDate);
    }

    private LocalDate requireStartDate(LocalDate startDate) {
        return Objects.requireNonNull(startDate, "startDate must not be null");
    }
}
