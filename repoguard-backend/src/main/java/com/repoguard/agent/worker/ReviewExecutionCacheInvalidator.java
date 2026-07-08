package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionCacheInvalidator {

    private final CacheEvictionService cacheEvictionService;

    ReviewExecutionCacheInvalidator(CacheEvictionService cacheEvictionService) {
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
    }

    void reviewTaskChanged() {
        cacheEvictionService.evictDashboardReviewActivity();
    }
}
