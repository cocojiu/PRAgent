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
