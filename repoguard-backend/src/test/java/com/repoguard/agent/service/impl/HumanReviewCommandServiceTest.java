package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;

class HumanReviewCommandServiceTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new HumanReviewCommandService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new HumanReviewCommandService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            new ReviewTaskStateMachine(),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    @Test
    void conflictStopsTimelineAndCacheSideEffects() {
        ReviewTaskTransitionStore transitionStore = org.mockito.Mockito.mock(ReviewTaskTransitionStore.class);
        ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("PENDING_HUMAN_REVIEW");
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        when(transitionStore.findById(42L)).thenReturn(task);
        doThrow(new BusinessException(ErrorCode.CONFLICT, ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE))
            .when(transitionStore)
            .completeHumanReview(
                org.mockito.ArgumentMatchers.eq(task),
                anyString(),
                anyString(),
                any(),
                anyString(),
                any()
            );
        HumanReviewCommandService service = new HumanReviewCommandService(
            transitionStore,
            timelineAppender,
            new ReviewTaskStateMachine(),
            cacheEvictionService
        );

        assertThatThrownBy(() -> service.submit(
            42L,
            new HumanReviewRequest("approve", "looks good"),
            "reviewer"
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                ((BusinessException) exception).getErrorCode()
            ).isEqualTo(ErrorCode.CONFLICT))
            .hasMessage(ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE);

        verifyNoInteractions(timelineAppender, cacheEvictionService);
    }
}
