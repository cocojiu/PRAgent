package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.ReviewTaskCommandService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class ReviewTaskCommandServiceImpl implements ReviewTaskCommandService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SOURCE_MANUAL_INPUT = "MANUAL_INPUT";
    private static final String SOURCE_GITHUB_PR_PICKER = "GITHUB_PR_PICKER";
    private static final String SOURCE_EXISTING_REUSED = "EXISTING_REUSED";
    private static final ConcurrentMap<String, CompletableFuture<ReviewTask>> IN_FLIGHT_MANUAL_CREATES = new ConcurrentHashMap<>();

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final RepoGuardMetrics metrics;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final TransactionTemplate manualCreateTransactionTemplate;

    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, metrics, cacheEvictionService, null, null);
    }

    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, metrics, cacheEvictionService, reviewTaskStateMachine, null);
    }

    @Autowired
    public ReviewTaskCommandServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        PlatformTransactionManager transactionManager
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.metrics = metrics;
        this.cacheEvictionService = cacheEvictionService;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.manualCreateTransactionTemplate = buildManualCreateTransactionTemplate(transactionManager);
    }

    private TransactionTemplate buildManualCreateTransactionTemplate(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return null;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    @Override
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        String organization = request.organization().trim();
        String repository = request.repository().trim();
        String commit = resolveCommit(request);
        String source = resolveTaskSource(request.source());
        ReviewTask existingTask = findExistingManualTask(organization, repository, request.prNumber(), commit);
        if (existingTask != null) {
            return reuseExistingTask(existingTask);
        }

        String idempotencyKey = manualIdempotencyKey(organization, repository, request.prNumber(), commit);
        CompletableFuture<ReviewTask> ownerFuture = new CompletableFuture<>();
        CompletableFuture<ReviewTask> existingFuture = IN_FLIGHT_MANUAL_CREATES.putIfAbsent(idempotencyKey, ownerFuture);
        if (existingFuture != null) {
            ReviewTask concurrentTask = awaitConcurrentManualTask(idempotencyKey, existingFuture);
            return reusedTaskResponse(concurrentTask);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        ReviewTask task = buildReviewTask(request, organization, repository, commit, source, createdAt);
        return executeManualCreateInTransaction(request, organization, repository, commit, source, idempotencyKey, ownerFuture, createdAt, task);
    }

    private ManualReviewResponse executeManualCreateInTransaction(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        String source,
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
            IN_FLIGHT_MANUAL_CREATES.remove(idempotencyKey, ownerFuture);
            throw ex;
        }
    }

    private ManualReviewResponse executeManualCreateTransaction(ManualReviewCreation creation) {
        if (manualCreateTransactionTemplate == null) {
            return creation.create();
        }
        return manualCreateTransactionTemplate.execute(status -> creation.create());
    }

    private ManualReviewResponse doCreateManualReview(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        String source,
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
        insertInitialTimeline(task.getId(), createdAt);
        completeManualCreateAfterTransaction(idempotencyKey, ownerFuture, task);
        evictDashboardOverview();
        if (metrics != null) {
            metrics.reviewTaskCreated(source);
        }
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(),
            organization,
            repository,
            request.prNumber(),
            commit,
            createdAt,
            LogContext.currentTraceId()
        );
        try {
            publishReviewTaskAfterCommit(task, message, createdAt);
            return new ManualReviewResponse(task.getId(), "queued", "Review task queued", false, lower(source), lower(source));
        } catch (MessagePublishException ex) {
            markPublishFailed(task, ex, createdAt);
            return new ManualReviewResponse(
                task.getId(),
                "publish_failed",
                "Review task saved, waiting for message publish compensation",
                false,
                lower(source),
                lower(source)
            );
        }
    }

    @Override
    @Transactional
    public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureHumanReviewAllowed(
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            resolveHumanReviewStatus(task)
        );

        String action = normalizeHumanReviewAction(request.action());
        LocalDateTime reviewedAt = LocalDateTime.now();
        String note = cleanHumanReviewNote(request.note());
        String humanReviewStatus = humanReviewStatusForAction(action);
        task.setStatus(taskStatusForHumanReview(humanReviewStatus));
        task.setHumanReviewStatus(humanReviewStatus);
        task.setHumanReviewNote(note);
        task.setHumanReviewBy(cleanOperator(operator));
        task.setHumanReviewedAt(reviewedAt);
        reviewTaskMapper.updateById(task);
        appendReviewTimeline(task.getId(), humanReviewTimelineLabel(humanReviewStatus, note), reviewedAt, "DONE");
        evictDashboardOverview();
        return humanReviewResponse(task, humanReviewMessage(humanReviewStatus));
    }

    @Override
    @Transactional
    public ReviewRetryResponse retryReview(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureRetryAllowed(task.getStatus());

        LocalDateTime queuedAt = LocalDateTime.now();
        int retryCount = task.getMqRetries() == null ? 1 : task.getMqRetries() + 1;
        resetTaskForRetry(task, retryCount);
        reviewTaskMapper.updateById(task);
        evictDashboardOverview();

        insertRetryTimeline(task.getId(), queuedAt);
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt,
            LogContext.currentTraceId()
        );
        try {
            publishReviewTaskAfterCommit(task, message, queuedAt);
            return new ReviewRetryResponse(task.getId(), "queued", "Review task queued for retry", retryCount);
        } catch (MessagePublishException ex) {
            markPublishFailed(task, ex, queuedAt);
            return new ReviewRetryResponse(
                task.getId(),
                "publish_failed",
                "Review task saved, waiting for message publish compensation",
                retryCount
            );
        }
    }

    private ReviewTask buildReviewTask(
        ManualReviewRequest request,
        String organization,
        String repository,
        String commit,
        String source,
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
        task.setSource(source);
        task.setTriggerSource(source);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setCreatedAt(createdAt);
        task.setDurationSeconds(0);
        return task;
    }

    private void resetTaskForRetry(ReviewTask task, int retryCount) {
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setMqRetries(retryCount);
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setLlmStatus(LlmStatus.PENDING.code());
        clearLlmQuality(task);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setHumanReviewNote(null);
        task.setHumanReviewBy(null);
        task.setHumanReviewedAt(null);
        task.setDurationSeconds(0);
    }

    private void publishReviewTaskAfterCommit(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reviewTaskPublisher.publish(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    reviewTaskPublisher.publish(message);
                } catch (MessagePublishException ex) {
                    markPublishFailed(task, ex, queuedAt);
                }
            }
        });
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }

    private ManualReviewResponse reuseExistingTask(ReviewTask existingTask) {
        return reusedTaskResponse(existingTask);
    }

    private ManualReviewResponse reusedTaskResponse(ReviewTask existingTask) {
        return new ManualReviewResponse(
            existingTask.getId(),
            lower(existingTask.getStatus()),
            "Review task already exists",
            true,
            lower(resolveStoredSource(existingTask.getSource())),
            lower(SOURCE_EXISTING_REUSED)
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
            IN_FLIGHT_MANUAL_CREATES.remove(idempotencyKey, future);
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
            IN_FLIGHT_MANUAL_CREATES.remove(idempotencyKey, future);
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
                }
                IN_FLIGHT_MANUAL_CREATES.remove(idempotencyKey, future);
            }
        });
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

    private void insertInitialTimeline(Long taskId, LocalDateTime createdAt) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel("Task queued");
        timeline.setEventTime(createdAt);
        timeline.setStatus("CURRENT");
        timeline.setSortOrder(1);
        reviewTimelineMapper.insert(timeline);
    }

    private void insertRetryTimeline(Long taskId, LocalDateTime queuedAt) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel("Retry queued");
        timeline.setEventTime(queuedAt);
        timeline.setStatus("CURRENT");
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private void appendReviewTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        clearLlmQuality(task);
        task.setPublishAttempts((task.getPublishAttempts() == null ? 0 : task.getPublishAttempts()) + 1);
        task.setNextPublishRetryAt(failedAt.plusSeconds(60));
        task.setLastPublishError(truncate(errorMessage(ex)));
        reviewTaskMapper.updateById(task);

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(task.getId());
        timeline.setLabel(truncate("Message publish failed: " + errorMessage(ex)));
        timeline.setEventTime(failedAt);
        timeline.setStatus("FAILED");
        timeline.setSortOrder(nextTimelineSortOrder(task.getId()));
        reviewTimelineMapper.insert(timeline);
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
    }

    private void clearLlmQuality(ReviewTask task) {
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(null);
        task.setLlmPromptSummary(null);
    }

    private String resolveTitle(ManualReviewRequest request) {
        if (StringUtils.hasText(request.title())) {
            return request.title().trim();
        }
        return "Manual review for PR #" + request.prNumber();
    }

    private String resolveCommit(ManualReviewRequest request) {
        if (StringUtils.hasText(request.commit())) {
            return request.commit().trim();
        }
        return "pending";
    }

    private String resolveBranch(ManualReviewRequest request) {
        if (StringUtils.hasText(request.branch())) {
            return request.branch().trim();
        }
        return "unknown";
    }

    private String resolveTaskSource(String source) {
        if (!StringUtils.hasText(source)) {
            return SOURCE_MANUAL_INPUT;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case SOURCE_GITHUB_PR_PICKER -> SOURCE_GITHUB_PR_PICKER;
            default -> SOURCE_MANUAL_INPUT;
        };
    }

    private String resolveStoredSource(String source) {
        return StringUtils.hasText(source) ? source : SOURCE_MANUAL_INPUT;
    }

    private HumanReviewResponse humanReviewResponse(ReviewTask task, String message) {
        return new HumanReviewResponse(
            task.getId(),
            lower(task.getStatus()),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt()),
            message
        );
    }

    private String normalizeHumanReviewAction(String action) {
        return action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
    }

    private String cleanHumanReviewNote(String note) {
        return StringUtils.hasText(note) ? note.trim() : null;
    }

    private String cleanOperator(String operator) {
        return StringUtils.hasText(operator) ? truncate(operator.trim()) : "unknown";
    }

    private String humanReviewStatusForAction(String action) {
        HumanReviewStatus humanReviewStatus = HumanReviewStatus.fromAction(action);
        if (humanReviewStatus == HumanReviewStatus.UNKNOWN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported human review action: " + action);
        }
        return humanReviewStatus.code();
    }

    private String taskStatusForHumanReview(String humanReviewStatus) {
        return reviewTaskStateMachine.statusAfterHumanReview(humanReviewStatus);
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (StringUtils.hasText(task.getHumanReviewStatus())) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
    }

    private String humanReviewTimelineLabel(String humanReviewStatus, String note) {
        String base = switch (HumanReviewStatus.from(humanReviewStatus)) {
            case APPROVED -> "Human review approved";
            case CHANGES_REQUESTED -> "Human review requested changes";
            case REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
        return StringUtils.hasText(note) ? truncate(base + ": " + note) : base;
    }

    private String humanReviewMessage(String humanReviewStatus) {
        return switch (HumanReviewStatus.from(humanReviewStatus)) {
            case APPROVED -> "Human review approved";
            case CHANGES_REQUESTED -> "Human review requested changes";
            case REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String buildPrUrl(ManualReviewRequest request) {
        return "https://github.com/"
            + request.organization().trim()
            + "/"
            + request.repository().trim()
            + "/pull/"
            + request.prNumber();
    }

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface ManualReviewCreation {
        ManualReviewResponse create();
    }
}
