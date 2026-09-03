package com.repoguard.agent.dashboard;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.review.quality.LlmQualityComparisonProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DashboardQualityFacade implements LlmQualityComparisonProvider {

    private final DashboardLlmQualityStatsAssembler llmQualityStatsAssembler;
    private final DashboardLlmQualityTrendBuilder llmQualityTrendBuilder;
    private final DashboardReviewTrendWindow reviewTrendWindow;
    private final DashboardSnapshotStore snapshotStore;
    private final DashboardDailySnapshotService dailySnapshotService;

    public DashboardQualityFacade(
        DashboardLlmQualityStatsAssembler llmQualityStatsAssembler,
        DashboardLlmQualityTrendBuilder llmQualityTrendBuilder,
        DashboardReviewTrendWindow reviewTrendWindow,
        DashboardSnapshotStore snapshotStore,
        DashboardDailySnapshotService dailySnapshotService
    ) {
        this.llmQualityStatsAssembler =
            Objects.requireNonNull(llmQualityStatsAssembler, "llmQualityStatsAssembler must not be null");
        this.llmQualityTrendBuilder =
            Objects.requireNonNull(llmQualityTrendBuilder, "llmQualityTrendBuilder must not be null");
        this.reviewTrendWindow = Objects.requireNonNull(reviewTrendWindow, "reviewTrendWindow must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.dailySnapshotService =
            Objects.requireNonNull(dailySnapshotService, "dailySnapshotService must not be null");
    }

    public DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_LLM_QUALITY + ":" + DashboardLlmTrendDays.normalize(llmTrendDays),
            () -> buildLlmQuality(llmTrendDays)
        );
    }

    private DashboardLlmQualityResponse buildLlmQuality(Integer llmTrendDays) {
        LocalDate latestReviewDate = dailySnapshotService.latestReviewDate();
        LocalDate reviewTrendStartDate = reviewTrendWindow.startDate(latestReviewDate);
        DashboardLlmQualityTrendBuilder.Window llmTrendWindow =
            llmQualityTrendBuilder.window(llmTrendDays, latestReviewDate);
        List<DashboardLlmQualityModelStat> modelStats =
            dailySnapshotService.selectLlmQualityByModelStats(reviewTrendStartDate);
        List<DashboardLlmQualityRepositoryStat> repositoryStats =
            dailySnapshotService.selectLlmQualityByRepositoryStats(reviewTrendStartDate);
        List<DashboardLlmQualityTrendCount> trendCounts =
            dailySnapshotService.selectLlmQualityTrendCounts(llmTrendWindow.startDate());
        return new DashboardLlmQualityResponse(
            llmQualityStatsAssembler.assembleByModel(modelStats),
            llmQualityStatsAssembler.assembleByRepository(repositoryStats),
            llmQualityTrendBuilder.build(trendCounts, llmTrendWindow)
        );
    }
}
