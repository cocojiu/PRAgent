package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionCacheInvalidator {

    private final CacheEvictionService cacheEvictionService;

    ReviewExecutionCacheInvalidator(CacheEvictionService cacheEvictionService) {
        this.cacheEvictionService = cacheEvictionService;
    }

    void reviewTaskChanged() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }
}
