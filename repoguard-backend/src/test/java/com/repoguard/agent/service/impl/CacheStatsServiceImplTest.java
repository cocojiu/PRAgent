package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.CacheConfig;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardDailySnapshotService;
import com.repoguard.agent.dto.CacheStatsItemDto;
import com.repoguard.agent.dto.CacheStatsResponse;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;

class CacheStatsServiceImplTest {

    private final CacheManager cacheManager = initializedCacheManager();
    private final CacheStatsServiceImpl service = new CacheStatsServiceImpl(cacheManager);
    private final CacheEvictionService evictionService = new CacheEvictionService(cacheManager);

    @Test
    void getStatsReportsCaffeineHitMissAndSize() {
        Cache cache = cacheManager.getCache(CacheNames.DASHBOARD_OVERVIEW);
        assertThat(cache).isNotNull();

        cache.put("default", "overview");
        assertThat(cache.get("default")).isNotNull();
        assertThat(cache.get("missing")).isNull();

        CacheStatsItemDto dashboardStats = statsFor(CacheNames.DASHBOARD_OVERVIEW);
        assertThat(dashboardStats.estimatedSize()).isEqualTo(1);
        assertThat(dashboardStats.requestCount()).isEqualTo(2);
        assertThat(dashboardStats.hitCount()).isEqualTo(1);
        assertThat(dashboardStats.missCount()).isEqualTo(1);
        assertThat(dashboardStats.hitRate()).isEqualTo(0.5d);
    }

    @Test
    void evictionServiceClearsNamedCache() {
        Cache cache = cacheManager.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        assertThat(cache).isNotNull();

        cache.put("repo", "pullRequests");
        assertThat(cache.get("repo")).isNotNull();

        evictionService.evictGithubOpenPullRequests();

        assertThat(cache.get("repo")).isNull();
        assertThat(statsFor(CacheNames.GITHUB_OPEN_PULL_REQUESTS).estimatedSize()).isZero();
    }

    @Test
    void dashboardEvictionClearsOverviewAndModuleCaches() {
        Cache overview = cacheManager.getCache(CacheNames.DASHBOARD_OVERVIEW);
        Cache summary = cacheManager.getCache(CacheNames.DASHBOARD_SUMMARY);
        Cache llmQuality = cacheManager.getCache(CacheNames.DASHBOARD_LLM_QUALITY);
        assertThat(overview).isNotNull();
        assertThat(summary).isNotNull();
        assertThat(llmQuality).isNotNull();

        overview.put("overview", "value");
        summary.put("summary", "value");
        llmQuality.put("7", "value");

        evictionService.evictDashboardOverview();

        assertThat(overview.get("overview")).isNull();
        assertThat(summary.get("summary")).isNull();
        assertThat(llmQuality.get("7")).isNull();
    }

    @Test
    void dashboardEvictionRefreshesPersistedSnapshots() {
        DashboardDailySnapshotService snapshotService = Mockito.mock(DashboardDailySnapshotService.class);
        CacheEvictionService eviction = new CacheEvictionService(cacheManager, objectProvider(snapshotService));

        eviction.evictDashboardOverview();

        Mockito.verify(snapshotService).refreshCurrentWindows();
    }

    @Test
    void dashboardCachesEmitHitMissMetricsButOtherCachesDoNot() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CacheManager observedCacheManager = initializedCacheManager(meterRegistry);

        for (String cacheName : dashboardCacheNames()) {
            Cache cache = observedCacheManager.getCache(cacheName);
            assertThat(cache).isNotNull();

            cache.put("key", "value");
            assertThat(cache.get("key")).isNotNull();
            assertThat(cache.get("missing")).isNull();

            assertThat(counter(
                meterRegistry,
                "repoguard.dashboard.cache.access",
                "cache", cacheName.toLowerCase(),
                "result", "hit"
            )).isEqualTo(1.0d);
            assertThat(counter(
                meterRegistry,
                "repoguard.dashboard.cache.access",
                "cache", cacheName.toLowerCase(),
                "result", "miss"
            )).isEqualTo(1.0d);
        }

        Cache githubCache = observedCacheManager.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        assertThat(githubCache).isNotNull();
        githubCache.put("repo", "pullRequests");
        assertThat(githubCache.get("repo")).isNotNull();

        assertThat(meterRegistry.find("repoguard.dashboard.cache.access")
            .tag("cache", CacheNames.GITHUB_OPEN_PULL_REQUESTS.toLowerCase())
            .counter()).isNull();
    }

    private CacheStatsItemDto statsFor(String cacheName) {
        CacheStatsResponse response = service.getStats();
        return response.caches().stream()
            .filter(item -> cacheName.equals(item.name()))
            .findFirst()
            .orElseThrow();
    }

    private static CacheManager initializedCacheManager() {
        return initializedCacheManager(new SimpleMeterRegistry());
    }

    private static CacheManager initializedCacheManager(SimpleMeterRegistry meterRegistry) {
        RepoGuardMetrics metrics = new RepoGuardMetrics(
            meterRegistry,
            new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
        );
        SimpleCacheManager cacheManager = (SimpleCacheManager) new CacheConfig().cacheManager(metrics);
        cacheManager.afterPropertiesSet();
        return cacheManager;
    }

    private static List<String> dashboardCacheNames() {
        return List.of(
            CacheNames.DASHBOARD_OVERVIEW,
            CacheNames.DASHBOARD_SUMMARY,
            CacheNames.DASHBOARD_REVIEW_TREND,
            CacheNames.DASHBOARD_RISK_DISTRIBUTION,
            CacheNames.DASHBOARD_RULES,
            CacheNames.DASHBOARD_HIGH_RISK_REVIEWS,
            CacheNames.DASHBOARD_LLM_QUALITY
        );
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }

    private static ObjectProvider<DashboardDailySnapshotService> objectProvider(
        DashboardDailySnapshotService snapshotService
    ) {
        return new ObjectProvider<>() {
            @Override
            public DashboardDailySnapshotService getObject(Object... args) {
                return snapshotService;
            }

            @Override
            public DashboardDailySnapshotService getIfAvailable() {
                return snapshotService;
            }

            @Override
            public DashboardDailySnapshotService getIfUnique() {
                return snapshotService;
            }

            @Override
            public DashboardDailySnapshotService getObject() {
                return snapshotService;
            }

            @Override
            public java.util.Iterator<DashboardDailySnapshotService> iterator() {
                return List.of(snapshotService).iterator();
            }

            @Override
            public java.util.stream.Stream<DashboardDailySnapshotService> stream() {
                return java.util.stream.Stream.of(snapshotService);
            }

            @Override
            public java.util.stream.Stream<DashboardDailySnapshotService> orderedStream() {
                return java.util.stream.Stream.of(snapshotService);
            }
        };
    }
}
