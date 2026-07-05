package com.repoguard.agent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
            caffeineCache(CacheNames.DASHBOARD_OVERVIEW, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_SUMMARY, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_REVIEW_TREND, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_RISK_DISTRIBUTION, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_RULES, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.DASHBOARD_LLM_QUALITY, Duration.ofSeconds(30), 256),
            caffeineCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS, Duration.ofSeconds(60), 128),
            caffeineCache(CacheNames.REVIEW_RULES, Duration.ofMinutes(10), 64)
        ));
        return cacheManager;
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
