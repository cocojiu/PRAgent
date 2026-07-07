package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.config.CacheEvictionService;
import org.junit.jupiter.api.Test;

class ReviewExecutionCacheInvalidatorTest {

    @Test
    void reviewTaskChangedEvictsDashboardOverview() {
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        ReviewExecutionCacheInvalidator invalidator = new ReviewExecutionCacheInvalidator(cacheEvictionService);

        invalidator.reviewTaskChanged();

        verify(cacheEvictionService).evictDashboardOverview();
    }

    @Test
    void requiresCacheEvictionServiceDependency() {
        assertThatThrownBy(() -> new ReviewExecutionCacheInvalidator(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
