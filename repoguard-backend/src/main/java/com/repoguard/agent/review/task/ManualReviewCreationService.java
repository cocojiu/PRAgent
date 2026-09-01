package com.repoguard.agent.review.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewRepositoryDimensionService;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.task.ManualReviewCreationGate.Claim;
import com.repoguard.agent.review.task.ReviewTaskCreationAssembler.CreationCommand;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantQuotaService;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class ManualReviewCreationService {
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final RepoGuardMetrics metrics;
    private final CacheEvictionService cacheEvictionService;
    private final TransactionTemplate manualCreateTransactionTemplate;
    private final ManualReviewCreationGate creationGate;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final ReviewRepositoryDimensionService repositoryDimensionService;
    private final ReviewPullRequestGenerationCoordinator generationCoordinator;
    private final ReviewTaskCreationAssembler creationAssembler;
    private final TenantQuotaService tenantQuotaService;
    public ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        PlatformTransactionManager transactionManager,
        ManualReviewCreationGate creationGate,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService,
        ReviewPullRequestGenerationCoordinator generationCoordinator,
        ReviewTaskCreationAssembler creationAssembler
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineAppender,
            metrics,
            cacheEvictionService,
            buildManualCreateTransactionTemplate(transactionManager),
            creationGate,
            reviewTaskAfterCommitPublisher,
            repositoryDimensionService,
            Objects.requireNonNull(generationCoordinator, "generationCoordinator"),
            creationAssembler,
            null
        );
    }
    @Autowired
    public ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        PlatformTransactionManager transactionManager,
        ManualReviewCreationGate creationGate,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService,
        ReviewPullRequestGenerationCoordinator generationCoordinator,
        ReviewTaskCreationAssembler creationAssembler,
        ObjectProvider<TenantQuotaService> tenantQuotaServiceProvider
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineAppender,
            metrics,
            cacheEvictionService,
            buildManualCreateTransactionTemplate(transactionManager),
            creationGate,
            reviewTaskAfterCommitPublisher,
            repositoryDimensionService,
            Objects.requireNonNull(generationCoordinator, "generationCoordinator"),
            creationAssembler,
            Objects.requireNonNull(tenantQuotaServiceProvider, "tenantQuotaServiceProvider")
                .getIfAvailable()
        );
    }
    public ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        PlatformTransactionManager transactionManager,
        ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineAppender,
            metrics,
            cacheEvictionService,
            buildManualCreateTransactionTemplate(transactionManager),
            new ManualReviewCreationGate(manualReviewIdempotencyCoordinator),
            reviewTaskAfterCommitPublisher,
            repositoryDimensionService,
            null,
            new ReviewTaskCreationAssembler(reviewTaskStateMachine),
            null
        );
    }
    ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        TransactionTemplate manualCreateTransactionTemplate,
        ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineAppender,
            metrics,
            cacheEvictionService,
            manualCreateTransactionTemplate,
            new ManualReviewCreationGate(manualReviewIdempotencyCoordinator),
            reviewTaskAfterCommitPublisher,
            repositoryDimensionService,
            null,
            new ReviewTaskCreationAssembler(reviewTaskStateMachine),
            null
        );
    }
    ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        TransactionTemplate manualCreateTransactionTemplate,
        ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService,
        ReviewPullRequestGenerationCoordinator generationCoordinator
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineAppender,
            metrics,
            cacheEvictionService,
            manualCreateTransactionTemplate,
            new ManualReviewCreationGate(manualReviewIdempotencyCoordinator),
            reviewTaskAfterCommitPublisher,
            repositoryDimensionService,
            generationCoordinator,
            new ReviewTaskCreationAssembler(reviewTaskStateMachine),
            null
        );
    }
    private ManualReviewCreationService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        TransactionTemplate manualCreateTransactionTemplate,
        ManualReviewCreationGate creationGate,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        ReviewRepositoryDimensionService repositoryDimensionService,
        ReviewPullRequestGenerationCoordinator generationCoordinator,
        ReviewTaskCreationAssembler creationAssembler,
        TenantQuotaService tenantQuotaService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.manualCreateTransactionTemplate = Objects.requireNonNull(
            manualCreateTransactionTemplate,
            "manualCreateTransactionTemplate"
        );
        this.creationGate = Objects.requireNonNull(creationGate, "creationGate");
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.repositoryDimensionService = Objects.requireNonNull(
            repositoryDimensionService,
            "repositoryDimensionService"
        );
        this.generationCoordinator = generationCoordinator;
        this.creationAssembler = Objects.requireNonNull(creationAssembler, "creationAssembler");
        this.tenantQuotaService = tenantQuotaService;
    }
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        return triggerReview(request, null);
    }
    public ManualReviewResponse triggerWebhookReview(ManualReviewRequest request, LocalDateTime headUpdatedAt) {
        Objects.requireNonNull(headUpdatedAt, "headUpdatedAt");
        return triggerReview(request, headUpdatedAt);
    }
    private ManualReviewResponse triggerReview(ManualReviewRequest request, LocalDateTime headUpdatedAt) {
        CreationCommand command = creationAssembler.command(request, LocalDateTime.now());
        Claim claim = creationGate.claim(command.idempotencyKey());
        if (!claim.owner()) {
            return creationAssembler.reusedResponse(creationGate.awaitExisting(claim));
        }
        ReviewTask existingTask = findExistingManualTask(command);
        if (existingTask != null) {
            creationGate.completeImmediately(claim, existingTask);
            return creationAssembler.reusedResponse(existingTask);
        }
        ReviewTask task = creationAssembler.task(command);
        return executeManualCreateInTransaction(command, claim, headUpdatedAt, task);
    }
    private static TransactionTemplate buildManualCreateTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
    private ManualReviewResponse executeManualCreateInTransaction(
        CreationCommand command,
        Claim claim,
        LocalDateTime headUpdatedAt,
        ReviewTask task
    ) {
        try {
            return executeManualCreateTransaction(() -> doCreateManualReview(command, claim, headUpdatedAt, task));
        } catch (RuntimeException ex) {
            creationGate.fail(claim, ex);
            throw ex;
        }
    }
    private ManualReviewResponse executeManualCreateTransaction(ManualReviewCreation creation) {
        return manualCreateTransactionTemplate.execute(status -> creation.create());
    }
    private ManualReviewResponse doCreateManualReview(
        CreationCommand command,
        Claim claim,
        LocalDateTime headUpdatedAt,
        ReviewTask task
    ) {
        ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult generationResult = advanceGeneration(
            command,
            headUpdatedAt,
            task
        );
        try {
            int affectedRows = reviewTaskMapper.insertManualReview(task);
            if (affectedRows != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Review task insert affected an unexpected row count");
            }
        } catch (DuplicateKeyException duplicateKeyException) {
            ReviewTask concurrentTask = findExistingManualTask(command);
            if (concurrentTask == null) {
                throw duplicateKeyException;
            }
            creationGate.completeAfterTransaction(claim, concurrentTask);
            return creationAssembler.reusedResponse(concurrentTask);
        }
        if (tenantQuotaService != null) {
            tenantQuotaService.reserveReview(TenantContext.currentTenantIdOrDefault());
        }
        boolean staleWebhook = generationResult != null && !generationResult.accepted();
        supersedeOlderPending(command, task, staleWebhook);
        appendInitialTimeline(task, command.createdAt(), staleWebhook);
        repositoryDimensionService.recordRepository(command.organization(), command.repository(), command.createdAt());
        creationGate.completeAfterTransaction(claim, task);
        evictDashboardReviewActivity(command.createdAt());
        metrics.reviewTaskCreated(command.source().code());
        if (staleWebhook) {
            return creationAssembler.staleWebhookResponse(task, command.source());
        }
        ReviewTaskMessage message = creationAssembler.message(task, command);
        boolean queued = reviewTaskAfterCommitPublisher.publishAfterCommit(task, message, command.createdAt());
        return creationAssembler.publishResponse(task, command.source(), queued);
    }
    private ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult advanceGeneration(
        CreationCommand command,
        LocalDateTime headUpdatedAt,
        ReviewTask task
    ) {
        if (command.source() != ReviewTaskSource.GITHUB_WEBHOOK || generationCoordinator == null) {
            return null;
        }
        if (headUpdatedAt == null) {
            throw new IllegalArgumentException("GitHub webhook headUpdatedAt is required");
        }
        ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult result = generationCoordinator.advance(
            command.organization(),
            command.repository(),
            command.prNumber(),
            command.commit(),
            command.createdAt(),
            headUpdatedAt
        );
        task.setGeneration(result.generation());
        if (!result.accepted()) {
            creationAssembler.markStaleWebhook(task, command.createdAt(), result.latestCommitSha());
        }
        return result;
    }
    private void supersedeOlderPending(CreationCommand command, ReviewTask task, boolean staleWebhook) {
        if (
            command.source() != ReviewTaskSource.GITHUB_WEBHOOK
                || generationCoordinator == null
                || staleWebhook
        ) {
            return;
        }
        generationCoordinator.supersedeOlderPending(
            command.organization(),
            command.repository(),
            command.prNumber(),
            task.getGeneration(),
            command.commit(),
            command.createdAt()
        );
    }
    private void appendInitialTimeline(ReviewTask task, LocalDateTime createdAt, boolean staleWebhook) {
        if (!staleWebhook) {
            reviewTimelineAppender.appendInitial(task.getId(), "Task queued", createdAt);
            return;
        }
        String message = task.getLlmFallbackReason();
        reviewTimelineAppender.appendInitial(task.getId(), message, createdAt);
        reviewTimelineAppender.completeCurrentAndAppend(task.getId(), message, createdAt, ReviewTimelineStatus.DONE);
    }
    private ReviewTask findExistingManualTask(CreationCommand command) {
        return reviewTaskMapper.selectOne(
            new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getOrganization, command.organization())
                .eq(ReviewTask::getRepository, command.repository())
                .eq(ReviewTask::getPrNumber, command.prNumber())
                .eq(ReviewTask::getCommitSha, command.commit())
                .last("limit 1")
        );
    }
    private void evictDashboardReviewActivity(LocalDateTime taskCreatedAt) {
        cacheEvictionService.evictDashboardReviewActivity(taskCreatedAt.toLocalDate());
    }
    @FunctionalInterface
    private interface ManualReviewCreation {
        ManualReviewResponse create();
    }
}
