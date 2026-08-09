package com.repoguard.agent.dashboard;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.mapper.DashboardMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DashboardOverviewFacade {

    private final DashboardMapper dashboardMapper;
    private final DashboardMetricAssembler dashboardMetricAssembler;
    private final DashboardReviewTrendAssembler reviewTrendAssembler;
    private final DashboardRiskDistributionAssembler riskDistributionAssembler;
    private final DashboardRuleAssembler dashboardRuleAssembler;
    private final DashboardHighRiskReviewAssembler highRiskReviewAssembler;
    private final DashboardReviewTrendWindow reviewTrendWindow;
    private final DashboardSnapshotStore snapshotStore;
    private final DashboardDailySnapshotService dailySnapshotService;
    private final DashboardQualityFacade qualityFacade;

    public DashboardOverviewFacade(
        DashboardMapper dashboardMapper,
        DashboardMetricAssembler dashboardMetricAssembler,
        DashboardReviewTrendAssembler reviewTrendAssembler,
        DashboardRiskDistributionAssembler riskDistributionAssembler,
        DashboardRuleAssembler dashboardRuleAssembler,
        DashboardHighRiskReviewAssembler highRiskReviewAssembler,
        DashboardReviewTrendWindow reviewTrendWindow,
        DashboardSnapshotStore snapshotStore,
        DashboardDailySnapshotService dailySnapshotService,
        DashboardQualityFacade qualityFacade
    ) {
        this.dashboardMapper = Objects.requireNonNull(dashboardMapper, "dashboardMapper must not be null");
        this.dashboardMetricAssembler =
            Objects.requireNonNull(dashboardMetricAssembler, "dashboardMetricAssembler must not be null");
        this.reviewTrendAssembler =
            Objects.requireNonNull(reviewTrendAssembler, "reviewTrendAssembler must not be null");
        this.riskDistributionAssembler =
            Objects.requireNonNull(riskDistributionAssembler, "riskDistributionAssembler must not be null");
        this.dashboardRuleAssembler =
            Objects.requireNonNull(dashboardRuleAssembler, "dashboardRuleAssembler must not be null");
        this.highRiskReviewAssembler =
            Objects.requireNonNull(highRiskReviewAssembler, "highRiskReviewAssembler must not be null");
        this.reviewTrendWindow = Objects.requireNonNull(reviewTrendWindow, "reviewTrendWindow must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.dailySnapshotService =
            Objects.requireNonNull(dailySnapshotService, "dailySnapshotService must not be null");
        this.qualityFacade = Objects.requireNonNull(qualityFacade, "qualityFacade must not be null");
    }

    public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_OVERVIEW + ":" + DashboardLlmTrendDays.normalize(llmTrendDays),
            () -> buildOverview(llmTrendDays)
        );
    }

    public List<DashboardMetricDto> getSummary() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_SUMMARY + ":summary",
            () -> dashboardMetricAssembler.assemble(dailySnapshotService.selectMetricStat(reviewTrendStartDate()))
        );
    }

    public List<ReviewTrendPointDto> getReviewTrend() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_REVIEW_TREND + ":reviewTrend",
            () -> reviewTrendAssembler.assemble(dailySnapshotService.selectReviewTrendCounts(reviewTrendStartDate()))
        );
    }

    public List<ChartSliceDto> getRiskDistribution() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_RISK_DISTRIBUTION + ":riskDistribution",
            () -> riskDistributionAssembler.assemble(
                dailySnapshotService.selectRiskLevelCounts(reviewTrendStartDate())
            )
        );
    }

    public DashboardRulesResponse getRules() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_RULES + ":rules",
            () -> dashboardRuleAssembler.assemble(
                dailySnapshotService.selectRuleHitCounts(reviewTrendStartDate())
            )
        );
    }

    public List<HighRiskReviewDto> getHighRiskReviews() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_HIGH_RISK_REVIEWS + ":highRiskReviews",
            () -> highRiskReviewAssembler.assemble(
                DashboardMapperProjectionAssembler.toHighRiskReviewDtos(
                    dashboardMapper.selectRecentHighRiskReviews(reviewTrendStartDate())
                )
            )
        );
    }

    private DashboardOverviewResponse buildOverview(Integer llmTrendDays) {
        DashboardRulesResponse rules = getRules();
        var llmQuality = qualityFacade.getLlmQuality(llmTrendDays);
        return new DashboardOverviewResponse(
            getSummary(),
            getReviewTrend(),
            getRiskDistribution(),
            rules.ruleHits(),
            getHighRiskReviews(),
            rules.failedRules(),
            List.of(),
            llmQuality.byModel(),
            llmQuality.byRepository(),
            llmQuality.trend()
        );
    }

    private LocalDate reviewTrendStartDate() {
        return reviewTrendWindow.startDate(dailySnapshotService.latestReviewDate());
    }
}
