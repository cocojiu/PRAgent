package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardOverviewFacade;
import com.repoguard.agent.dashboard.DashboardQualityFacade;
import com.repoguard.agent.dashboard.DashboardSystemHealthProbe;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.DashboardService;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardOverviewFacade overviewFacade;
    private final DashboardQualityFacade qualityFacade;
    private final DashboardSystemHealthProbe systemHealthProbe;

    public DashboardServiceImpl(
        DashboardOverviewFacade overviewFacade,
        DashboardQualityFacade qualityFacade,
        DashboardSystemHealthProbe systemHealthProbe
    ) {
        this.overviewFacade = Objects.requireNonNull(overviewFacade, "overviewFacade must not be null");
        this.qualityFacade = Objects.requireNonNull(qualityFacade, "qualityFacade must not be null");
        this.systemHealthProbe = Objects.requireNonNull(systemHealthProbe, "systemHealthProbe must not be null");
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_OVERVIEW,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
        return overviewFacade.getOverview(llmTrendDays);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_SUMMARY, key = "'summary'", sync = true)
    public List<DashboardMetricDto> getSummary() {
        return overviewFacade.getSummary();
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_REVIEW_TREND, key = "'reviewTrend'", sync = true)
    public List<ReviewTrendPointDto> getReviewTrend() {
        return overviewFacade.getReviewTrend();
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RISK_DISTRIBUTION, key = "'riskDistribution'", sync = true)
    public List<ChartSliceDto> getRiskDistribution() {
        return overviewFacade.getRiskDistribution();
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RULES, key = "'rules'", sync = true)
    public DashboardRulesResponse getRules() {
        return overviewFacade.getRules();
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, key = "'highRiskReviews'", sync = true)
    public List<HighRiskReviewDto> getHighRiskReviews() {
        return overviewFacade.getHighRiskReviews();
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_LLM_QUALITY,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
        return qualityFacade.getLlmQuality(llmTrendDays);
    }

    @Override
    public List<SystemHealthItemDto> getSystemHealth() {
        return systemHealthProbe.probe();
    }
}
