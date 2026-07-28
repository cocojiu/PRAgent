package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.cache.CacheEvictionService;
import org.junit.jupiter.api.Test;

class ReviewExecutionCacheInvalidatorTest {

    @Test
    void reviewTaskChangedEvictsDashboardReviewActivity() {
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        ReviewExecutionCacheInvalidator invalidator = new ReviewExecutionCacheInvalidator(cacheEvictionService);

        invalidator.reviewTaskChanged();

        verify(cacheEvictionService).evictDashboardReviewActivity();
    }

    @Test
    void requiresCacheEvictionServiceDependency() {
        assertThatThrownBy(() -> new ReviewExecutionCacheInvalidator(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
