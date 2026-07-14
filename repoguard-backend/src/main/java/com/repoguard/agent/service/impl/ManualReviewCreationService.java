package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public class ManualReviewCreationService {

    private static final long COMPLETED_MANUAL_CREATE_RETENTION_SECONDS = 5;

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final RepoGuardMetrics metrics;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final TransactionTemplate manualCreateTransactionTemplate;
    private final ManualReviewIdempotencyCoordinator manualReviewIdempotencyCoordinator;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final ReviewRepositoryDimensionService repositoryDimensionService;

    @Autowired
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
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.manualCreateTransactionTemplate = buildManualCreateTransactionTemplate(transactionManager);
        this.manualReviewIdempotencyCoordinator = Objects.requireNonNull(
            manualReviewIdempotencyCoordinator,
            "manualReviewIdempotencyCoordinator"
        );
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.repositoryDimensionService = Objects.requireNonNull(
            repositoryDimensionService,
            "repositoryDimensionService"
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
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.manualCreateTransactionTemplate = Objects.requireNonNull(
            manualCreateTransactionTemplate,
            "manualCreateTransactionTemplate"
        );
        this.manualReviewIdempotencyCoordinator = Objects.requireNonNull(
            manualReviewIdempotencyCoordinator,
            "manualReviewIdempotencyCoordinator"
        );
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.repositoryDimensionService = Objects.requireNonNull(
            repositoryDimensionService,
            "repositoryDimensionService"
        );
    }

    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        String organization = request.organization().trim();
        String repository = request.repository().trim();
        String commit = resolveCommit(request);
        ReviewTaskSource source = ReviewTaskSource.creationSource(request.source());

        String idempotencyKey = manualIdempotencyKey(organization, repository, request.prNumber(), commit);
        CompletableFuture<ReviewTask> ownerFuture = new CompletableFuture<>();
        CompletableFuture<ReviewTask> existingFuture = manualReviewIdempotencyCoordinator.registerOwner(
            idempotencyKey,
            ownerFuture
        );
        if (existingFuture != null) {
            ReviewTask concurrentTask = awaitConcurrentManualTask(idempotencyKey, existingFuture);
            return reusedTaskResponse(concurrentTask);
        }

        ReviewTask existingTask = findExistingManualTask(organization, repository, request.prNumber(), commit);
        if (existingTask != null) {
            ownerFuture.complete(existingTask);
            manualReviewIdempotencyCoordinator.remove(idempotencyKey, ownerFuture);
            return reusedTaskResponse(existingTask);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        ReviewTask task = buildReviewTask(request, organization, repository, commit, source, createdAt);
        return executeManualCreateInTransaction(
            request,
            organization,
            repository,
            commit,
            source,
            idempotencyKey,
            ownerFuture,
            createdAt,
            task
        );
    }

    private TransactionTemplate buildManualCreateTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private ManualReviewResponse executeManualCreateInTransaction(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        ReviewTaskSource source,
        String idempotencyKey,
        CompletableFuture<ReviewTask> ownerFuture,
        LocalDateTime createdAt,
        ReviewTask task
    ) {
        try {
            return executeManualCreateTransaction(() ->
                doCreateManualReview(request, organization, repository, commit, source, idempotencyKey, ownerFuture, createdAt, task)
            );
        } catch (RuntimeException ex) {
            ownerFuture.completeExceptionally(ex);
            manualReviewIdempotencyCoordinator.remove(idempotencyKey, ownerFuture);
            throw ex;
        }
    }

    private ManualReviewResponse executeManualCreateTransaction(ManualReviewCreation creation) {
        return manualCreateTransactionTemplate.execute(status -> creation.create());
    }

    private ManualReviewResponse doCreateManualReview(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        ReviewTaskSource source,
        String idempotencyKey,
        CompletableFuture<ReviewTask> ownerFuture,
        LocalDateTime createdAt,
        ReviewTask task
    ) {
        int affectedRows = reviewTaskMapper.insertManualReviewOrReuse(task);
        if (affectedRows != 1) {
            ReviewTask concurrentTask = findExistingManualTask(organization, repository, request.prNumber(), commit);
            if (concurrentTask == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Review task idempotency conflict could not be resolved");
            }
            completeManualCreateAfterTransaction(idempotencyKey, ownerFuture, concurrentTask);
            return reusedTaskResponse(concurrentTask);
        }
        reviewTimelineAppender.appendInitial(task.getId(), "Task queued", createdAt);
        repositoryDimensionService.recordRepository(organization, repository, createdAt);
        completeManualCreateAfterTransaction(idempotencyKey, ownerFuture, task);
        evictDashboardReviewActivity();
        metrics.reviewTaskCreated(source.code());
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(),
            organization,
            repository,
            request.prNumber(),
            commit,
            createdAt,
            LogContext.currentTraceId()
        );
        boolean queued = reviewTaskAfterCommitPublisher.publishAfterCommit(task, message, createdAt);
        if (queued) {
            return new ManualReviewResponse(task.getId(), "queued", "Review task queued", false, source.dtoCode(), source.dtoCode());
        }
        return new ManualReviewResponse(
            task.getId(),
            "publish_failed",
            "Review task saved, waiting for message publish compensation",
            false,
            source.dtoCode(),
            source.dtoCode()
        );
    }

    private ReviewTask buildReviewTask(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        ReviewTaskSource source,
        LocalDateTime createdAt
    ) {
        ReviewTask task = new ReviewTask();
        task.setPrNumber(request.prNumber());
        task.setTitle(resolveTitle(request));
        task.setRepository(repository);
        task.setOrganization(organization);
        task.setCommitSha(commit);
        task.setBranchName(resolveBranch(request));
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setMqRetries(0);
        task.setPublishAttempts(0);
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPrUrl(buildPrUrl(request));
        task.setSource(source.code());
        task.setTriggerSource(source.code());
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setCreatedAt(createdAt);
        task.setDurationSeconds(0);
        return task;
    }

    private ManualReviewResponse reusedTaskResponse(ReviewTask existingTask) {
        return new ManualReviewResponse(
            existingTask.getId(),
            lower(existingTask.getStatus()),
            "Review task already exists",
            true,
            ReviewTaskSource.dtoCodeOrDefault(existingTask.getSource()),
            ReviewTaskSource.EXISTING_REUSED.dtoCode()
        );
    }

    private String manualIdempotencyKey(String organization, String repository, Integer prNumber, String commit) {
        return organization + '\n' + repository + '\n' + prNumber + '\n' + commit;
    }

    private ReviewTask awaitConcurrentManualTask(String idempotencyKey, CompletableFuture<ReviewTask> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interrupted while waiting for existing review task");
        } catch (ExecutionException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Concurrent review task creation failed");
        } catch (TimeoutException ex) {
            manualReviewIdempotencyCoordinator.remove(idempotencyKey, future);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Timed out waiting for existing review task");
        }
    }

    private void completeManualCreateAfterTransaction(
        String idempotencyKey,
        CompletableFuture<ReviewTask> future,
        ReviewTask task
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            future.complete(task);
            manualReviewIdempotencyCoordinator.remove(idempotencyKey, future);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                future.complete(task);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    future.completeExceptionally(new IllegalStateException("Manual review transaction rolled back"));
                    manualReviewIdempotencyCoordinator.remove(idempotencyKey, future);
                    return;
                }
                scheduleManualCreateCleanup(idempotencyKey, future);
            }
        });
    }

    private void scheduleManualCreateCleanup(String idempotencyKey, CompletableFuture<ReviewTask> future) {
        manualReviewIdempotencyCoordinator.scheduleRemove(
            idempotencyKey,
            future,
            COMPLETED_MANUAL_CREATE_RETENTION_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private ReviewTask findExistingManualTask(String organization, String repository, Integer prNumber, String commit) {
        if (!StringUtils.hasText(commit)) {
            return null;
        }
        return reviewTaskMapper.selectOne(
            new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getOrganization, organization)
                .eq(ReviewTask::getRepository, repository)
                .eq(ReviewTask::getPrNumber, prNumber)
                .eq(ReviewTask::getCommitSha, commit)
                .last("limit 1")
        );
    }

    private String resolveTitle(ManualReviewRequest request) {
        if (StringUtils.hasText(request.title())) {
            return request.title().trim();
        }
        return "Manual review for PR #" + request.prNumber();
    }

    private String resolveCommit(ManualReviewRequest request) {
        if (!StringUtils.hasText(request.commit())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Commit SHA is required");
        }
        String commit = request.commit().trim();
        if (!commit.matches("(?i)^[0-9a-f]{40}([0-9a-f]{24})?$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Commit SHA must be a 40 or 64 character hexadecimal value");
        }
        return commit.toLowerCase(Locale.ROOT);
    }

    private String resolveBranch(ManualReviewRequest request) {
        if (StringUtils.hasText(request.branch())) {
            return request.branch().trim();
        }
        return "unknown";
    }

    private String buildPrUrl(ManualReviewRequest request) {
        return "https://github.com/"
            + request.organization().trim()
            + "/"
            + request.repository().trim()
            + "/pull/"
            + request.prNumber();
    }

    private void evictDashboardReviewActivity() {
        cacheEvictionService.evictDashboardReviewActivity();
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface ManualReviewCreation {
        ManualReviewResponse create();
    }
}
