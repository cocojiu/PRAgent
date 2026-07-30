package com.repoguard.agent.review.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewRepositoryDimensionService;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ManualReviewCreationServiceTest {

    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

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
            COMMIT_SHA,
            "main",
            "manual_input"
        ));

        verify(repositoryDimensionService).recordRepository(
            org.mockito.Mockito.eq("codex"),
            org.mockito.Mockito.eq("repo-guard"),
            any()
        );
    }

    @Test
    void concurrentWaiterDoesNotReceiveTaskWhenOwnerTransactionRollsBack() throws Exception {
        ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
        ReviewTaskAfterCommitPublisher afterCommitPublisher =
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class);
        CountDownLatch publisherEntered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        ObservingManualReviewIdempotencyCoordinator coordinator =
            new ObservingManualReviewIdempotencyCoordinator();

        when(taskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(7002L);
            return 1;
        }).when(taskMapper).insertManualReviewOrReuse(any(ReviewTask.class));
        when(afterCommitPublisher.publishAfterCommit(any(ReviewTask.class), any(), any())).thenAnswer(invocation -> {
            publisherEntered.countDown();
            await(releaseFailure);
            throw new IllegalStateException("force transaction rollback");
        });

        ManualReviewCreationService service = new ManualReviewCreationService(
            taskMapper,
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(CacheEvictionService.class),
            new ReviewTaskStateMachine(),
            new TransactionTemplate(new RecordingTransactionManager()),
            coordinator,
            afterCommitPublisher,
            org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class)
        );
        ManualReviewRequest request = new ManualReviewRequest(
            "codex",
            "repo-guard",
            43,
            "Rollback concurrency",
            COMMIT_SHA,
            "main",
            "manual_input"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<ManualReviewResponse> owner = CompletableFuture.supplyAsync(
                () -> service.triggerManualReview(request),
                executor
            );
            assertThat(publisherEntered.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<ManualReviewResponse> waiter = CompletableFuture.supplyAsync(
                () -> service.triggerManualReview(request),
                executor
            );
            assertThat(coordinator.duplicateRegistered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(waiter.isDone()).isFalse();

            releaseFailure.countDown();

            assertThatThrownBy(() -> owner.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> waiter.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(com.repoguard.agent.common.BusinessException.class);
        } finally {
            releaseFailure.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static class ObservingManualReviewIdempotencyCoordinator extends ManualReviewIdempotencyCoordinator {

        private final CountDownLatch duplicateRegistered = new CountDownLatch(1);

        ObservingManualReviewIdempotencyCoordinator() {
            super(org.mockito.Mockito.mock(ScheduledExecutorService.class));
        }

        @Override
        public CompletableFuture<ReviewTask> registerOwner(
            String idempotencyKey,
            CompletableFuture<ReviewTask> ownerFuture
        ) {
            CompletableFuture<ReviewTask> existing = super.registerOwner(idempotencyKey, ownerFuture);
            if (existing != null) {
                duplicateRegistered.countDown();
            }
            return existing;
        }
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

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
