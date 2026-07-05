package com.repoguard.agent.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
public class CacheEvictionService {

    private final CacheManager cacheManager;

    public CacheEvictionService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictDashboardOverview() {
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
}
