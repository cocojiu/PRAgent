package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.CacheConfig;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.CacheStatsItemDto;
import com.repoguard.agent.dto.CacheStatsResponse;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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

    private CacheStatsItemDto statsFor(String cacheName) {
        CacheStatsResponse response = service.getStats();
        return response.caches().stream()
            .filter(item -> cacheName.equals(item.name()))
            .findFirst()
            .orElseThrow();
    }

    private static CacheManager initializedCacheManager() {
        RepoGuardMetrics metrics = new RepoGuardMetrics(new SimpleMeterRegistry());
        SimpleCacheManager cacheManager = (SimpleCacheManager) new CacheConfig().cacheManager(metrics);
        cacheManager.afterPropertiesSet();
        return cacheManager;
    }
}
