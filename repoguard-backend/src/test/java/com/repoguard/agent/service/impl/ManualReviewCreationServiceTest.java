package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
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
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
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
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
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
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }

    @Test
    void constructorRejectsMissingTransactionTemplate() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            (TransactionTemplate) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("manualCreateTransactionTemplate");
    }

    @Test
    void constructorRejectsMissingTransactionManager() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            (PlatformTransactionManager) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("transactionManager");
    }

    @Test
    void constructorRejectsMissingRepositoryDimensionService() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            org.mockito.Mockito.mock(TransactionTemplate.class),
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("repositoryDimensionService");
    }

    @Test
    void recordsRepositoryDimensionWhenManualReviewTaskIsCreated() {
        ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
        ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
        ReviewRepositoryDimensionService repositoryDimensionService =
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class);
        ReviewTaskAfterCommitPublisher afterCommitPublisher =
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class);
        when(taskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(7001L);
            return 1;
        }).when(taskMapper).insertManualReviewOrReuse(any(ReviewTask.class));
        when(afterCommitPublisher.publishAfterCommit(any(ReviewTask.class), any(), any())).thenReturn(true);
        ManualReviewCreationService service = new ManualReviewCreationService(
            taskMapper,
            timelineAppender,
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            new TransactionTemplate(new RecordingTransactionManager()),
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            afterCommitPublisher,
            repositoryDimensionService
        );

        service.triggerManualReview(new ManualReviewRequest(
            " codex ",
            " repo-guard ",
            42,
            "Repository dimension",
            "abcdef0",
            "main",
            "manual_input"
        ));

        verify(repositoryDimensionService).recordRepository(
            org.mockito.Mockito.eq("codex"),
            org.mockito.Mockito.eq("repo-guard"),
            any()
        );
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
