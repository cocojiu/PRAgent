package com.repoguard.agent.worker;

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
    void noopsWhenCacheEvictionServiceIsUnavailable() {
        ReviewExecutionCacheInvalidator invalidator = new ReviewExecutionCacheInvalidator(null);

        invalidator.reviewTaskChanged();
    }
}
