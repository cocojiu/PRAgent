package com.repoguard.agent.config;

import com.repoguard.agent.dashboard.DashboardDailySnapshotService;
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

    private final CacheManager cacheManager;
    private final Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier;

    public CacheEvictionService(CacheManager cacheManager) {
        this(cacheManager, () -> null);
    }

    @Autowired
    public CacheEvictionService(
        CacheManager cacheManager,
        ObjectProvider<DashboardDailySnapshotService> dashboardSnapshotServiceProvider
    ) {
        this(cacheManager, dashboardSnapshotServiceProvider::getIfAvailable);
    }

    private CacheEvictionService(
        CacheManager cacheManager,
        Supplier<DashboardDailySnapshotService> dashboardSnapshotServiceSupplier
    ) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager must not be null");
        this.dashboardSnapshotServiceSupplier =
            Objects.requireNonNull(dashboardSnapshotServiceSupplier, "dashboardSnapshotServiceSupplier must not be null");
    }

    public void evictDashboardOverview() {
        refreshDashboardSnapshotsAfterCommit();
        clear(CacheNames.DASHBOARD_OVERVIEW);
        clear(CacheNames.DASHBOARD_SUMMARY);
        clear(CacheNames.DASHBOARD_REVIEW_TREND);
        clear(CacheNames.DASHBOARD_RISK_DISTRIBUTION);
        clear(CacheNames.DASHBOARD_RULES);
        clear(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS);
        clear(CacheNames.DASHBOARD_LLM_QUALITY);
    }

    public void evictGithubOpenPullRequests() {
        clear(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
    }

    public void evictReviewRules() {
        clear(CacheNames.REVIEW_RULES);
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private void refreshDashboardSnapshotsAfterCommit() {
        Runnable refresh = this::refreshDashboardSnapshots;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
            return;
        }
        refresh.run();
    }

    private void refreshDashboardSnapshots() {
        DashboardDailySnapshotService snapshotService = dashboardSnapshotServiceSupplier.get();
        if (snapshotService == null) {
            return;
        }
        try {
            snapshotService.refreshCurrentWindows();
        } catch (RuntimeException ex) {
            LOGGER.warn("Dashboard daily snapshot refresh failed during cache eviction", ex);
        }
    }
}
