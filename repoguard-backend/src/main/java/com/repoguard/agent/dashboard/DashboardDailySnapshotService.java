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
import com.repoguard.agent.mapper.projection.DashboardProjections.SnapshotRefreshState;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class DashboardDailySnapshotService {

    private static final int DIRTY_REFRESH_BATCH_SIZE = 128;
    private static final LocalDate MYSQL_MIN_DATE = LocalDate.of(1000, 1, 1);

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
        return DashboardMapperProjectionAssembler.toDto(snapshotMapper.selectMetricStat(startDate));
    }

    public List<DashboardReviewTrendCount> selectReviewTrendCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return DashboardMapperProjectionAssembler.toReviewTrendDtos(snapshotMapper.selectReviewTrendCounts(startDate));
    }

    public List<DashboardRiskLevelCount> selectRiskLevelCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return DashboardMapperProjectionAssembler.toRiskLevelDtos(snapshotMapper.selectRiskLevelCounts(startDate));
    }

    public List<DashboardRuleHitCount> selectRuleHitCounts(LocalDate startDate) {
        ensureReviewSnapshot(startDate);
        return DashboardMapperProjectionAssembler.toRuleHitDtos(snapshotMapper.selectRuleHitCounts(startDate));
    }

    public List<DashboardLlmQualityTrendCount> selectLlmQualityTrendCounts(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return DashboardMapperProjectionAssembler.toLlmQualityTrendDtos(
            snapshotMapper.selectLlmQualityTrendCounts(startDate)
        );
    }

    public List<DashboardLlmQualityModelStat> selectLlmQualityByModelStats(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return DashboardMapperProjectionAssembler.toLlmQualityModelDtos(
            snapshotMapper.selectLlmQualityByModelStats(startDate)
        );
    }

    public List<DashboardLlmQualityRepositoryStat> selectLlmQualityByRepositoryStats(LocalDate startDate) {
        ensureLlmQualitySnapshot(startDate);
        return DashboardMapperProjectionAssembler.toLlmQualityRepositoryDtos(
            snapshotMapper.selectLlmQualityByRepositoryStats(startDate)
        );
    }

    @Transactional
    public void refreshCurrentWindows() {
        refreshDirtySnapshots(DIRTY_REFRESH_BATCH_SIZE);
        refreshCurrentReviewWindow();
        refreshCurrentLlmQualityWindow();
    }

    public void markReviewActivityDirty(LocalDate statDate) {
        snapshotMapper.markReviewActivityDirty(requireStartDate(statDate));
    }

    public void markLlmQualityDirty(LocalDate statDate) {
        snapshotMapper.markLlmQualityDirty(requireStartDate(statDate));
    }

    @Transactional
    public void refreshDate(LocalDate statDate) {
        SnapshotRefreshState state = snapshotMapper.selectRefreshState(requireStartDate(statDate));
        if (state != null) {
            refreshDirtyState(state);
        }
    }

    @Transactional
    public int refreshDirtySnapshots(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, DIRTY_REFRESH_BATCH_SIZE));
        List<SnapshotRefreshState> states = snapshotMapper.selectDirtyRefreshStates(
            MYSQL_MIN_DATE,
            normalizedLimit
        );
        states.forEach(this::refreshDirtyState);
        return states.size();
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
        LocalDate latestDate = latestReviewDate();
        forEachDate(normalizedStartDate, latestDate, this::rebuildReviewDate);
    }

    @Transactional
    public void refreshLlmQualitySnapshot(LocalDate startDate) {
        LocalDate normalizedStartDate = requireStartDate(startDate);
        LocalDate latestDate = latestReviewDate();
        forEachDate(normalizedStartDate, latestDate, this::rebuildLlmQualityDate);
    }

    private void ensureReviewSnapshot(LocalDate startDate) {
        refreshDirtySnapshotsFrom(startDate);
        LocalDate latestReviewDate = latestReviewDate();
        if (latestReviewDate == null) {
            return;
        }
        LocalDate latestSnapshotDate = snapshotMapper.selectLatestReviewSnapshotDate();
        if (latestSnapshotDate == null || latestSnapshotDate.isBefore(latestReviewDate)) {
            refreshReviewSnapshot(startDate);
        }
    }

    private void ensureLlmQualitySnapshot(LocalDate startDate) {
        refreshDirtySnapshotsFrom(startDate);
        LocalDate latestSourceDate = snapshotMapper.selectLatestLlmQualitySourceDate();
        if (latestSourceDate == null || latestSourceDate.isBefore(requireStartDate(startDate))) {
            return;
        }
        LocalDate latestSnapshotDate = snapshotMapper.selectLatestLlmQualitySnapshotDate();
        if (latestSnapshotDate == null || latestSnapshotDate.isBefore(latestSourceDate)) {
            refreshLlmQualitySnapshot(startDate);
        }
    }

    private void refreshDirtySnapshotsFrom(LocalDate startDate) {
        List<SnapshotRefreshState> states = snapshotMapper.selectDirtyRefreshStates(
            requireStartDate(startDate),
            DIRTY_REFRESH_BATCH_SIZE
        );
        states.forEach(this::refreshDirtyState);
    }

    private void refreshDirtyState(SnapshotRefreshState state) {
        if (state.reviewDirty()) {
            rebuildReviewDate(state.statDate());
            snapshotMapper.markReviewRefreshed(state.statDate(), state.reviewVersion());
        }
        if (state.llmQualityDirty()) {
            rebuildLlmQualityDate(state.statDate());
            snapshotMapper.markLlmQualityRefreshed(state.statDate(), state.llmQualityVersion());
        }
    }

    private void rebuildReviewDate(LocalDate statDate) {
        snapshotMapper.deleteReviewDailyStatsOn(statDate);
        snapshotMapper.insertReviewDailyStatsForDate(statDate);
        snapshotMapper.deleteRuleDailyStatsOn(statDate);
        snapshotMapper.insertRuleDailyStatsForDate(statDate);
    }

    private void rebuildLlmQualityDate(LocalDate statDate) {
        snapshotMapper.deleteLlmQualityDailyStatsOn(statDate);
        snapshotMapper.insertLlmQualityDailyStatsForDate(statDate);
    }

    private void forEachDate(
        LocalDate startDate,
        LocalDate endDate,
        java.util.function.Consumer<LocalDate> refresher
    ) {
        if (endDate == null || startDate.isAfter(endDate)) {
            return;
        }
        for (LocalDate statDate = startDate; !statDate.isAfter(endDate); statDate = statDate.plusDays(1)) {
            ScheduledJobLeaseContext.assertHeld();
            refresher.accept(statDate);
        }
    }

    private LocalDate requireStartDate(LocalDate startDate) {
        return Objects.requireNonNull(startDate, "startDate must not be null");
    }
}
