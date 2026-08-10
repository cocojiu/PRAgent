package com.repoguard.agent.cache;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardDailySnapshotService;
import com.repoguard.agent.dashboard.DashboardSnapshotStore;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CacheEvictionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheEvictionService.class);
    private static final String REVIEW_ACTIVITY_REFRESH_KEY = "maintenance:daily-review-activity";
    private static final String FEEDBACK_QUALITY_REFRESH_KEY = "maintenance:daily-feedback-quality";
    private static final String QUALITY_BASELINE_REFRESH_KEY = "maintenance:review-quality-baseline";

    private final CacheManager cacheManager;
    private final Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier;
    private final Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier;
    private final Supplier<ReviewQualityBaselineService> qualityBaselineServiceSupplier;

    public CacheEvictionService(CacheManager cacheManager) {
        this(cacheManager, () -> null, () -> null, () -> null);
    }

    @Autowired
    public CacheEvictionService(
        CacheManager cacheManager,
        ObjectProvider<DashboardDailySnapshotService> dashboardSnapshotServiceProvider,
        ObjectProvider<DashboardSnapshotStore> dashboardSnapshotStoreProvider,
        ObjectProvider<ReviewQualityBaselineService> qualityBaselineServiceProvider
    ) {
        this(
            cacheManager,
            dashboardSnapshotServiceProvider::getIfAvailable,
            dashboardSnapshotStoreProvider::getIfAvailable,
            qualityBaselineServiceProvider::getIfAvailable
        );
    }

    public CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier,
        Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier
    ) {
        this(cacheManager, dashboardSnapshotServiceSupplier, dashboardSnapshotStoreSupplier, () -> null);
    }

    public CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier,
        Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier,
        Supplier<ReviewQualityBaselineService> qualityBaselineServiceSupplier
    ) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager must not be null");
        this.dashboardSnapshotServiceSupplier =
            Objects.requireNonNull(dashboardSnapshotServiceSupplier, "dashboardSnapshotServiceSupplier must not be null");
        this.dashboardSnapshotStoreSupplier =
            Objects.requireNonNull(dashboardSnapshotStoreSupplier, "dashboardSnapshotStoreSupplier must not be null");
        this.qualityBaselineServiceSupplier = Objects.requireNonNull(
            qualityBaselineServiceSupplier,
            "qualityBaselineServiceSupplier must not be null"
        );
    }

    public void evictDashboardOverview() {
        evictDashboardReviewActivity();
    }

    public void evictDashboardReviewActivity() {
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        LocalDate latestReviewDate = snapshotService == null ? null : snapshotService.latestReviewDate();
        if (latestReviewDate == null) {
            markQualityBaselineDirty();
            afterCommitOrNow(() -> evictDashboardReviewActivityNow(null));
            return;
        }
        evictDashboardReviewActivity(latestReviewDate);
    }

    public void evictDashboardReviewActivity(LocalDate statDate) {
        LocalDate normalizedStatDate = Objects.requireNonNull(statDate, "statDate must not be null");
        markDashboardSnapshotDirty(service -> service.markReviewActivityDirty(normalizedStatDate));
        markQualityBaselineDirty();
        afterCommitOrNow(() -> evictDashboardReviewActivityNow(normalizedStatDate));
    }

    private void evictDashboardReviewActivityNow(LocalDate statDate) {
        if (statDate != null) {
            submitDashboardRefresh(
                REVIEW_ACTIVITY_REFRESH_KEY + ":" + statDate,
                () -> refreshDashboardSnapshots(service -> service.refreshDate(statDate))
            );
        }
        submitQualityBaselineRefresh();
        clear(CacheNames.REVIEW_TASK_LIST_SUMMARY);
        evictDashboardOverviewCompatibilityNow();
        evictDashboardSummary();
        evictDashboardReviewTrend();
        evictDashboardRiskDistribution();
        evictDashboardRulesNow();
        evictDashboardHighRiskReviews();
        evictDashboardLlmQuality();
    }

    public void evictDashboardFeedbackQuality() {
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        LocalDate latestReviewDate = snapshotService == null ? null : snapshotService.latestReviewDate();
        if (latestReviewDate == null) {
            markQualityBaselineDirty();
            afterCommitOrNow(() -> evictDashboardFeedbackQualityNow(null));
            return;
        }
        evictDashboardFeedbackQuality(latestReviewDate);
    }

    public void evictDashboardFeedbackQuality(LocalDate statDate) {
        LocalDate normalizedStatDate = Objects.requireNonNull(statDate, "statDate must not be null");
        markDashboardSnapshotDirty(service -> service.markLlmQualityDirty(normalizedStatDate));
        markQualityBaselineDirty();
        afterCommitOrNow(() -> evictDashboardFeedbackQualityNow(normalizedStatDate));
    }

    private void evictDashboardFeedbackQualityNow(LocalDate statDate) {
        if (statDate != null) {
            submitDashboardRefresh(
                FEEDBACK_QUALITY_REFRESH_KEY + ":" + statDate,
                () -> refreshDashboardSnapshots(service -> service.refreshDate(statDate))
            );
        }
        submitQualityBaselineRefresh();
        evictDashboardLlmQuality();
    }

    public void evictDashboardRules() {
        afterCommitOrNow(this::evictDashboardRulesNow);
    }

    private void evictDashboardRulesNow() {
        clear(CacheNames.DASHBOARD_RULES);
        evictSnapshot(CacheNames.DASHBOARD_RULES + ":rules");
    }

    public void evictDashboardOverviewCompatibility() {
        afterCommitOrNow(this::evictDashboardOverviewCompatibilityNow);
    }

    private void evictDashboardOverviewCompatibilityNow() {
        clear(CacheNames.DASHBOARD_OVERVIEW);
        evictSnapshotsByPrefix(CacheNames.DASHBOARD_OVERVIEW + ":");
    }

    public void evictGithubOpenPullRequests() {
        afterCommitOrNow(() -> clear(CacheNames.GITHUB_OPEN_PULL_REQUESTS));
    }

    public void evictReviewRules() {
        afterCommitOrNow(() -> clear(CacheNames.REVIEW_RULES));
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private void evictDashboardSummary() {
        clear(CacheNames.DASHBOARD_SUMMARY);
        evictSnapshot(CacheNames.DASHBOARD_SUMMARY + ":summary");
    }

    private void evictDashboardReviewTrend() {
        clear(CacheNames.DASHBOARD_REVIEW_TREND);
        evictSnapshot(CacheNames.DASHBOARD_REVIEW_TREND + ":reviewTrend");
    }

    private void evictDashboardRiskDistribution() {
        clear(CacheNames.DASHBOARD_RISK_DISTRIBUTION);
        evictSnapshot(CacheNames.DASHBOARD_RISK_DISTRIBUTION + ":riskDistribution");
    }

    private void evictDashboardHighRiskReviews() {
        clear(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS);
        evictSnapshot(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS + ":highRiskReviews");
    }

    private void evictDashboardLlmQuality() {
        clear(CacheNames.DASHBOARD_LLM_QUALITY);
        evictSnapshotsByPrefix(CacheNames.DASHBOARD_LLM_QUALITY + ":");
    }

    private void evictSnapshot(String key) {
        DashboardSnapshotStore snapshotStore = dashboardSnapshotStoreSupplier.get();
        if (snapshotStore != null) {
            snapshotStore.evict(key);
        }
    }

    private void evictSnapshotsByPrefix(String prefix) {
        DashboardSnapshotStore snapshotStore = dashboardSnapshotStoreSupplier.get();
        if (snapshotStore != null) {
            snapshotStore.evictByPrefix(prefix);
        }
    }

    private void afterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void submitDashboardRefresh(String refreshKey, Runnable refresh) {
        DashboardSnapshotStore snapshotStore = dashboardSnapshotStoreSupplier.get();
        if (snapshotStore == null) {
            refresh.run();
            return;
        }
        snapshotStore.executeAsync(refreshKey, refresh);
    }

    private void refreshDashboardSnapshots(java.util.function.Consumer<DashboardDailySnapshotService> refresher) {
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        if (snapshotService == null) {
            return;
        }
        try {
            refresher.accept(snapshotService);
        } catch (RuntimeException ex) {
            LOGGER.warn("Dashboard daily snapshot refresh failed during cache eviction", ex);
        }
    }

    private void markQualityBaselineDirty() {
        ReviewQualityBaselineService service = qualityBaselineServiceSupplier.get();
        if (service != null) {
            service.markDirty();
        }
    }

    private void submitQualityBaselineRefresh() {
        ReviewQualityBaselineService service = qualityBaselineServiceSupplier.get();
        if (service == null) {
            return;
        }
        DashboardSnapshotStore snapshotStore = dashboardSnapshotStoreSupplier.get();
        Runnable refresh = () -> {
            try {
                service.refreshIfDirty();
            } catch (RuntimeException ex) {
                LOGGER.warn("Review quality baseline refresh failed; dirty version remains retryable", ex);
            }
        };
        if (snapshotStore == null) {
            refresh.run();
            return;
        }
        snapshotStore.executeAsync(QUALITY_BASELINE_REFRESH_KEY, refresh);
    }

    private void markDashboardSnapshotDirty(
        java.util.function.Consumer<DashboardDailySnapshotService> dirtyMarker
    ) {
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        if (snapshotService != null) {
            dirtyMarker.accept(snapshotService);
        }
    }
}
