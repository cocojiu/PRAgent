package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewExecutionCacheInvalidatorTest {

    @Test
    void reviewTaskChangedEvictsDashboardReviewActivity() {
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        ReviewExecutionCacheInvalidator invalidator = new ReviewExecutionCacheInvalidator(cacheEvictionService);
        ReviewTask task = new ReviewTask();
        task.setCreatedAt(LocalDateTime.of(2026, 6, 19, 10, 30));

        invalidator.reviewTaskChanged(task);

        verify(cacheEvictionService).evictDashboardReviewActivity(LocalDate.of(2026, 6, 19));
    }

    @Test
    void requiresCacheEvictionServiceDependency() {
        assertThatThrownBy(() -> new ReviewExecutionCacheInvalidator(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
