package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class ManualReviewCreationServiceTest {

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            (TransactionTemplate) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            null,
            (TransactionTemplate) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            null,
            new ReviewTaskStateMachine(),
            (TransactionTemplate) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
