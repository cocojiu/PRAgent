package com.repoguard.agent.review.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestHeadProvider;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskRetryServiceTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskRetryService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            org.mockito.Mockito.mock(PullRequestHeadProvider.class)
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
            null,
            org.mockito.Mockito.mock(PullRequestHeadProvider.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    @Test
    void constructorRejectsMissingPullRequestHeadProvider() {
        assertThatThrownBy(() -> new ReviewTaskRetryService(
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            new ReviewTaskStateMachine(),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("pullRequestHeadProvider");
    }

    @Test
    void conflictStopsTimelinePublishAndCacheSideEffects() {
        ReviewTaskTransitionStore transitionStore = org.mockito.Mockito.mock(ReviewTaskTransitionStore.class);
        ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
        ReviewTaskAfterCommitPublisher publisher = org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class);
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        PullRequestHeadProvider pullRequestHeadProvider = org.mockito.Mockito.mock(PullRequestHeadProvider.class);
        ReviewTask task = reviewTask();
        task.setId(42L);
        task.setStatus("FAILED");
        task.setCommitSha("aaaaaaaa");
        task.setMqRetries(1);
        when(transitionStore.findById(42L)).thenReturn(task);
        doThrow(new BusinessException(ErrorCode.CONFLICT, ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE))
            .when(transitionStore)
            .retryReviewTask(task, 2, "aaaaaaaa");
        ReviewTaskRetryService service = new ReviewTaskRetryService(
            transitionStore,
            timelineAppender,
            new ReviewTaskStateMachine(),
            publisher,
            cacheEvictionService,
            pullRequestHeadProvider
        );

        assertThatThrownBy(() -> service.retry(42L))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                ((BusinessException) exception).getErrorCode()
            ).isEqualTo(ErrorCode.CONFLICT))
            .hasMessage(ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE);

        verifyNoInteractions(timelineAppender, publisher, cacheEvictionService, pullRequestHeadProvider);
    }

    @Test
    void supersededRetryRefreshesHeadAndPublishesMessageForLatestCommit() {
        ReviewTaskTransitionStore transitionStore = org.mockito.Mockito.mock(ReviewTaskTransitionStore.class);
        ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
        ReviewTaskAfterCommitPublisher publisher = org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class);
        CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
        PullRequestHeadProvider pullRequestHeadProvider = org.mockito.Mockito.mock(PullRequestHeadProvider.class);
        ReviewTask task = reviewTask();
        task.setId(42L);
        task.setOrganization("octocat");
        task.setRepository("api");
        task.setPrNumber(7);
        task.setStatus("SUPERSEDED");
        task.setCommitSha("aaaaaaaa");
        task.setMqRetries(1);
        when(transitionStore.findById(42L)).thenReturn(task);
        when(pullRequestHeadProvider.fetchPullRequestHeadSha(task)).thenReturn("bbbbbbbb");
        doAnswer(invocation -> {
            task.setStatus("QUEUED");
            task.setCommitSha("bbbbbbbb");
            return null;
        }).when(transitionStore).retryReviewTask(task, 2, "bbbbbbbb");
        when(publisher.publishAfterCommit(
            org.mockito.ArgumentMatchers.eq(task),
            org.mockito.ArgumentMatchers.any(ReviewTaskMessage.class),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);
        ReviewTaskRetryService service = new ReviewTaskRetryService(
            transitionStore,
            timelineAppender,
            new ReviewTaskStateMachine(),
            publisher,
            cacheEvictionService,
            pullRequestHeadProvider
        );

        var response = service.retry(42L);

        assertThat(response.status()).isEqualTo("queued");
        verify(pullRequestHeadProvider).fetchPullRequestHeadSha(task);
        verify(transitionStore).retryReviewTask(task, 2, "bbbbbbbb");
        ArgumentCaptor<ReviewTaskMessage> messageCaptor = ArgumentCaptor.forClass(ReviewTaskMessage.class);
        verify(publisher).publishAfterCommit(
            org.mockito.ArgumentMatchers.eq(task),
            messageCaptor.capture(),
            org.mockito.ArgumentMatchers.any()
        );
        assertThat(messageCaptor.getValue().commit()).isEqualTo("bbbbbbbb");
    }

    private ReviewTask reviewTask() {
        ReviewTask task = new ReviewTask();
        task.setCreatedAt(LocalDateTime.parse("2026-06-05T18:00:00"));
        return task;
    }
}
