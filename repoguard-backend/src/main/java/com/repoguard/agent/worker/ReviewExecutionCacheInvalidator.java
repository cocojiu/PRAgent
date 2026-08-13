package com.repoguard.agent.worker;

import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionCacheInvalidator {

    private final CacheEvictionService cacheEvictionService;

    ReviewExecutionCacheInvalidator(CacheEvictionService cacheEvictionService) {
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
    }

    void reviewTaskChanged(ReviewTask task) {
        Objects.requireNonNull(task, "task");
        cacheEvictionService.evictDashboardReviewActivity(task.getCreatedAt().toLocalDate());
    }
}
