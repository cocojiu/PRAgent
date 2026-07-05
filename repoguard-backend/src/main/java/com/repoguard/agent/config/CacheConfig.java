package com.repoguard.agent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.observability.ObservedCache;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Set<String> DASHBOARD_CACHE_NAMES = Set.of(
        CacheNames.DASHBOARD_OVERVIEW,
        CacheNames.DASHBOARD_SUMMARY,
        CacheNames.DASHBOARD_REVIEW_TREND,
        CacheNames.DASHBOARD_RISK_DISTRIBUTION,
        CacheNames.DASHBOARD_RULES,
        CacheNames.DASHBOARD_HIGH_RISK_REVIEWS,
        CacheNames.DASHBOARD_LLM_QUALITY
    );

    @Bean
    public CacheManager cacheManager(RepoGuardMetrics metrics) {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
            observedCache(CacheNames.DASHBOARD_OVERVIEW, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_SUMMARY, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_REVIEW_TREND, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_RISK_DISTRIBUTION, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_RULES, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.DASHBOARD_LLM_QUALITY, Duration.ofSeconds(30), 256, metrics),
            observedCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS, Duration.ofSeconds(60), 128, metrics),
            observedCache(CacheNames.REVIEW_RULES, Duration.ofMinutes(10), 64, metrics)
        ));
        return cacheManager;
    }

    private Cache observedCache(String name, Duration ttl, long maximumSize, RepoGuardMetrics metrics) {
        return new ObservedCache(
            caffeineCache(name, ttl, maximumSize),
            metrics,
            DASHBOARD_CACHE_NAMES.contains(name)
        );
    }

    private CaffeineCache caffeineCache(String name, Duration ttl, long maximumSize) {
        return new CaffeineCache(
            name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .recordStats()
                .build(),
            false
        );
    }
}
