package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;

class ReviewTaskRetryServiceTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskRetryService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new ReviewTaskRetryService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            new ReviewTaskStateMachine(),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    @Test
    void conflictStopsTimelinePublishAndCacheSideEffects() {
        ReviewTaskTransitionStore transitionStore = org.mockito.Mockito.mock(ReviewTaskTransitionStore.class);
        ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
        ReviewTaskAfterCommitPublisher publisher = org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class);
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("FAILED");
        task.setMqRetries(1);
        when(transitionStore.findById(42L)).thenReturn(task);
        doThrow(new BusinessException(ErrorCode.CONFLICT, ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE))
            .when(transitionStore)
            .retryFailedTask(task, 2);
        ReviewTaskRetryService service = new ReviewTaskRetryService(
            transitionStore,
            timelineAppender,
            new ReviewTaskStateMachine(),
            publisher,
            cacheEvictionService
        );

        assertThatThrownBy(() -> service.retry(42L))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                ((BusinessException) exception).getErrorCode()
            ).isEqualTo(ErrorCode.CONFLICT))
            .hasMessage(ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE);

        verifyNoInteractions(timelineAppender, publisher, cacheEvictionService);
    }
}
