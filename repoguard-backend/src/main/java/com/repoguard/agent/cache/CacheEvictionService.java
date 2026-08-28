package com.repoguard.agent.cache;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardDailySnapshotService;
import com.repoguard.agent.dashboard.DashboardSnapshotStore;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantScopedKey;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
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
    private static final Set<String> TENANT_CACHE_NAMES = Set.of(
        CacheNames.DASHBOARD_OVERVIEW,
        CacheNames.DASHBOARD_SUMMARY,
        CacheNames.DASHBOARD_REVIEW_TREND,
        CacheNames.DASHBOARD_RISK_DISTRIBUTION,
        CacheNames.DASHBOARD_RULES,
        CacheNames.DASHBOARD_HIGH_RISK_REVIEWS,
        CacheNames.DASHBOARD_LLM_QUALITY,
        CacheNames.REVIEW_TASK_LIST_SUMMARY,
        CacheNames.GITHUB_OPEN_PULL_REQUESTS,
        CacheNames.REVIEW_RULES
    );

    private final CacheManager cacheManager;
    private final Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier;
    private final Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier;
    private final Supplier<ReviewQualityBaselineService> qualityBaselineServiceSupplier;
    private final Supplier<ClusterCacheInvalidationPublisher> clusterInvalidationPublisherSupplier;

    public CacheEvictionService(CacheManager cacheManager) {
        this(cacheManager, () -> null, () -> null, () -> null, () -> null);
    }

    @Autowired
    public CacheEvictionService(
        CacheManager cacheManager,
        ObjectProvider<DashboardDailySnapshotService> dashboardSnapshotServiceProvider,
        ObjectProvider<DashboardSnapshotStore> dashboardSnapshotStoreProvider,
        ObjectProvider<ReviewQualityBaselineService> qualityBaselineServiceProvider,
        ObjectProvider<ClusterCacheInvalidationPublisher> clusterInvalidationPublisherProvider
    ) {
        this(
            cacheManager,
            dashboardSnapshotServiceProvider::getIfAvailable,
            dashboardSnapshotStoreProvider::getIfAvailable,
            qualityBaselineServiceProvider::getIfAvailable,
            clusterInvalidationPublisherProvider::getIfAvailable
        );
    }

    public CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier,
        Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier
    ) {
        this(cacheManager, dashboardSnapshotServiceSupplier, dashboardSnapshotStoreSupplier, () -> null, () -> null);
    }

    public CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier,
        Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier,
        Supplier<ReviewQualityBaselineService> qualityBaselineServiceSupplier
    ) {
        this(
            cacheManager,
            dashboardSnapshotServiceSupplier,
            dashboardSnapshotStoreSupplier,
            qualityBaselineServiceSupplier,
            () -> null
        );
    }

    CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier,
        Supplier<DashboardSnapshotStore> dashboardSnapshotStoreSupplier,
        Supplier<ReviewQualityBaselineService> qualityBaselineServiceSupplier,
        Supplier<ClusterCacheInvalidationPublisher> clusterInvalidationPublisherSupplier
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
        this.clusterInvalidationPublisherSupplier = Objects.requireNonNull(
            clusterInvalidationPublisherSupplier,
            "clusterInvalidationPublisherSupplier must not be null"
        );
    }

    public void evictDashboardOverview() {
        evictDashboardReviewActivity();
    }

    public void evictDashboardReviewActivity() {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        LocalDate latestReviewDate = snapshotService == null ? null : snapshotService.latestReviewDate();
        if (latestReviewDate == null) {
            markQualityBaselineDirty();
            publishClusterInvalidation(tenantId, ClusterCacheInvalidationType.DASHBOARD_REVIEW_ACTIVITY, null);
            afterCommitOrNow(() -> evictDashboardReviewActivityNow(null));
            return;
        }
        evictDashboardReviewActivity(latestReviewDate);
    }

    public void evictDashboardReviewActivity(LocalDate statDate) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDate normalizedStatDate = Objects.requireNonNull(statDate, "statDate must not be null");
        markDashboardSnapshotDirty(service -> service.markReviewActivityDirty(normalizedStatDate));
        markQualityBaselineDirty();
        publishClusterInvalidation(
            tenantId,
            ClusterCacheInvalidationType.DASHBOARD_REVIEW_ACTIVITY,
            normalizedStatDate
        );
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
        clearTenant(CacheNames.REVIEW_TASK_LIST_SUMMARY);
        evictDashboardOverviewCompatibilityNow();
        evictDashboardSummary();
        evictDashboardReviewTrend();
        evictDashboardRiskDistribution();
        evictDashboardRulesNow();
        evictDashboardHighRiskReviews();
        evictDashboardLlmQuality();
    }

    public void evictDashboardFeedbackQuality() {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        LocalDate latestReviewDate = snapshotService == null ? null : snapshotService.latestReviewDate();
        if (latestReviewDate == null) {
            markQualityBaselineDirty();
            publishClusterInvalidation(tenantId, ClusterCacheInvalidationType.DASHBOARD_FEEDBACK_QUALITY, null);
            afterCommitOrNow(() -> evictDashboardFeedbackQualityNow(null));
            return;
        }
        evictDashboardFeedbackQuality(latestReviewDate);
    }

    public void evictDashboardFeedbackQuality(LocalDate statDate) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDate normalizedStatDate = Objects.requireNonNull(statDate, "statDate must not be null");
        markDashboardSnapshotDirty(service -> service.markLlmQualityDirty(normalizedStatDate));
        markQualityBaselineDirty();
        publishClusterInvalidation(
            tenantId,
            ClusterCacheInvalidationType.DASHBOARD_FEEDBACK_QUALITY,
            normalizedStatDate
        );
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
        publishClusterInvalidation(
            TenantContext.currentTenantIdOrDefault(),
            ClusterCacheInvalidationType.DASHBOARD_RULES,
            null
        );
        afterCommitOrNow(this::evictDashboardRulesNow);
    }

    private void evictDashboardRulesNow() {
        clearTenant(CacheNames.DASHBOARD_RULES);
        evictSnapshot(CacheNames.DASHBOARD_RULES + ":rules");
    }

    public void evictDashboardOverviewCompatibility() {
        publishClusterInvalidation(
            TenantContext.currentTenantIdOrDefault(),
            ClusterCacheInvalidationType.DASHBOARD_OVERVIEW,
            null
        );
        afterCommitOrNow(this::evictDashboardOverviewCompatibilityNow);
    }

    private void evictDashboardOverviewCompatibilityNow() {
        clearTenant(CacheNames.DASHBOARD_OVERVIEW);
        evictSnapshotsByPrefix(CacheNames.DASHBOARD_OVERVIEW + ":");
    }

    public void evictGithubOpenPullRequests() {
        publishClusterInvalidation(
            TenantContext.currentTenantIdOrDefault(),
            ClusterCacheInvalidationType.GITHUB_OPEN_PULL_REQUESTS,
            null
        );
        afterCommitOrNow(() -> clearTenant(CacheNames.GITHUB_OPEN_PULL_REQUESTS));
    }

    public void evictReviewRules() {
        publishClusterInvalidation(
            TenantContext.currentTenantIdOrDefault(),
            ClusterCacheInvalidationType.REVIEW_RULES,
            null
        );
        afterCommitOrNow(() -> clearTenant(CacheNames.REVIEW_RULES));
    }

    void evictTenantLocal(long tenantId) {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenantId)) {
            TENANT_CACHE_NAMES.forEach(this::clearTenant);
            DashboardSnapshotStore snapshotStore = dashboardSnapshotStoreSupplier.get();
            if (snapshotStore != null) {
                snapshotStore.evictByPrefix("");
            }
        }
    }

    private void clearTenant(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        Object nativeCache = cache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            long tenantId = TenantContext.currentTenantIdOrDefault();
            caffeineCache.asMap().keySet().stream()
                .filter(key -> !(key instanceof TenantScopedKey scopedKey) || scopedKey.belongsTo(tenantId))
                .toList()
                .forEach(cache::evict);
            return;
        }
        cache.clear();
    }

    private void evictDashboardSummary() {
        clearTenant(CacheNames.DASHBOARD_SUMMARY);
        evictSnapshot(CacheNames.DASHBOARD_SUMMARY + ":summary");
    }

    private void evictDashboardReviewTrend() {
        clearTenant(CacheNames.DASHBOARD_REVIEW_TREND);
        evictSnapshot(CacheNames.DASHBOARD_REVIEW_TREND + ":reviewTrend");
    }

    private void evictDashboardRiskDistribution() {
        clearTenant(CacheNames.DASHBOARD_RISK_DISTRIBUTION);
        evictSnapshot(CacheNames.DASHBOARD_RISK_DISTRIBUTION + ":riskDistribution");
    }

    private void evictDashboardHighRiskReviews() {
        clearTenant(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS);
        evictSnapshot(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS + ":highRiskReviews");
    }

    private void evictDashboardLlmQuality() {
        clearTenant(CacheNames.DASHBOARD_LLM_QUALITY);
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

    private void publishClusterInvalidation(
        long tenantId,
        ClusterCacheInvalidationType type,
        LocalDate statDate
    ) {
        ClusterCacheInvalidationPublisher publisher = clusterInvalidationPublisherSupplier.get();
        if (publisher != null) {
            publisher.publish(tenantId, type, statDate);
        }
    }
}
