package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.ReviewTaskCommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskCommandServiceImpl implements ReviewTaskCommandService {

    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final HumanReviewCommandService humanReviewCommandService;
    private final ReviewTaskRetryService reviewTaskRetryService;
    private final ManualReviewCreationService manualReviewCreationService;

    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            metrics,
            cacheEvictionService,
            null,
            null,
            Runnable::run,
            new ManualReviewIdempotencyCoordinator(),
            null,
            null,
            null,
            null
        );
    }

    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            metrics,
            cacheEvictionService,
            reviewTaskStateMachine,
            null,
            Runnable::run,
            new ManualReviewIdempotencyCoordinator(),
            null,
            null,
            null,
            null
        );
    }

    @Autowired
    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        PlatformTransactionManager transactionManager,
        ReviewTaskAfterCommitPublisherExecutor reviewPublishExecutor,
        ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        HumanReviewCommandService humanReviewCommandService,
        ReviewTaskRetryService reviewTaskRetryService,
        ManualReviewCreationService manualReviewCreationService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            metrics,
            cacheEvictionService,
            reviewTaskStateMachine,
            transactionManager,
            (java.util.concurrent.Executor) reviewPublishExecutor,
            manualReviewIdempotencyCoordinator,
            reviewTaskAfterCommitPublisher,
            humanReviewCommandService,
            reviewTaskRetryService,
            manualReviewCreationService
        );
    }

    ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        PlatformTransactionManager transactionManager,
        java.util.concurrent.Executor reviewPublishExecutor,
        ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        HumanReviewCommandService humanReviewCommandService,
        ReviewTaskRetryService reviewTaskRetryService,
        ManualReviewCreationService manualReviewCreationService
    ) {
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        ManualReviewIdempotencyCoordinator idempotencyCoordinator = manualReviewIdempotencyCoordinator == null
            ? new ManualReviewIdempotencyCoordinator()
            : manualReviewIdempotencyCoordinator;
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher == null
            ? new ReviewTaskAfterCommitPublisher(
                reviewTaskMapper,
                reviewTimelineMapper,
                reviewTaskPublisher,
                this.reviewTaskStateMachine,
                reviewPublishExecutor
            )
            : reviewTaskAfterCommitPublisher;
        this.humanReviewCommandService = humanReviewCommandService == null
            ? new HumanReviewCommandService(
                reviewTaskMapper,
                reviewTimelineMapper,
                this.reviewTaskStateMachine,
                cacheEvictionService
            )
            : humanReviewCommandService;
        this.reviewTaskRetryService = reviewTaskRetryService == null
            ? new ReviewTaskRetryService(
                reviewTaskMapper,
                reviewTimelineMapper,
                this.reviewTaskStateMachine,
                this.reviewTaskAfterCommitPublisher,
                cacheEvictionService
            )
            : reviewTaskRetryService;
        this.manualReviewCreationService = manualReviewCreationService == null
            ? new ManualReviewCreationService(
                reviewTaskMapper,
                new ReviewTimelineAppender(reviewTimelineMapper),
                metrics,
                cacheEvictionService,
                this.reviewTaskStateMachine,
                transactionManager,
                idempotencyCoordinator,
                this.reviewTaskAfterCommitPublisher
            )
            : manualReviewCreationService;
    }

    @Override
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        return manualReviewCreationService.triggerManualReview(request);
    }

    @Override
    @Transactional
    public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
        return humanReviewCommandService.submit(id, request, operator);
    }

    @Override
    @Transactional
    public ReviewRetryResponse retryReview(Long id) {
        return reviewTaskRetryService.retry(id);
    }

}
