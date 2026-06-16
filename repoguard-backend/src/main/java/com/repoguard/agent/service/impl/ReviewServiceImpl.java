package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.service.GithubCommentApplicationService;
import com.repoguard.agent.service.ReviewService;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewTaskQueryService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReviewServiceImpl implements ReviewService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String STATUS_PENDING_HUMAN_REVIEW = "PENDING_HUMAN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_CHANGES_REQUESTED = "CHANGES_REQUESTED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String HUMAN_REVIEW_PENDING = "PENDING";
    private static final String HUMAN_REVIEW_APPROVED = "APPROVED";
    private static final String HUMAN_REVIEW_CHANGES_REQUESTED = "CHANGES_REQUESTED";
    private static final String HUMAN_REVIEW_REJECTED = "REJECTED";
    private static final String HUMAN_REVIEW_NOT_REQUIRED = "NOT_REQUIRED";
    private static final FailureSummary NO_FAILURE_SUMMARY = new FailureSummary(null, null, null);

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final GithubPullRequestClient githubPullRequestClient;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewTaskQueryService reviewTaskQueryService;
    private final ReviewTaskCommandService reviewTaskCommandService;
    private final FindingFeedbackService findingFeedbackService;
    private final GithubCommentApplicationService githubCommentApplicationService;

    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        IntegrationConfigMapper integrationConfigMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            integrationConfigMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            githubPullRequestClient,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Autowired
    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        IntegrationConfigMapper integrationConfigMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        CacheEvictionService cacheEvictionService,
        ReviewTaskQueryService reviewTaskQueryService,
        ReviewTaskCommandService reviewTaskCommandService,
        FindingFeedbackService findingFeedbackService,
        GithubCommentApplicationService githubCommentApplicationService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.githubPullRequestClient = githubPullRequestClient;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
        this.cacheEvictionService = cacheEvictionService;
        this.reviewTaskQueryService = reviewTaskQueryService == null
            ? new ReviewTaskQueryServiceImpl(reviewTaskMapper, changedFileMapper, reviewFindingMapper, reviewTimelineMapper)
            : reviewTaskQueryService;
        this.reviewTaskCommandService = reviewTaskCommandService == null
            ? new ReviewTaskCommandServiceImpl(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, metrics, cacheEvictionService)
            : reviewTaskCommandService;
        this.findingFeedbackService = findingFeedbackService == null
            ? new FindingFeedbackServiceImpl(reviewTaskMapper, reviewFindingMapper, reviewTimelineMapper, cacheEvictionService)
            : findingFeedbackService;
        this.githubCommentApplicationService = githubCommentApplicationService == null
            ? new GithubCommentApplicationServiceImpl(
                reviewTaskMapper,
                changedFileMapper,
                reviewFindingMapper,
                githubCommentPublicationMapper,
                githubCommentPublicationBatchMapper,
                githubCommentPublicationBatchItemMapper,
                integrationConfigMapper
            )
            : githubCommentApplicationService;
    }

    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        IntegrationConfigMapper integrationConfigMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            integrationConfigMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            githubPullRequestClient,
            metrics,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        return reviewTaskQueryService.listReviews(query);
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long id) {
        LocalDateTime startedAt = LocalDateTime.now();
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        ensureGithubCommentPublishAllowed(task);

        GithubCommentPreviewResponse preview = getGithubCommentPreview(id);
        List<GithubCommentPublishItem> skippedItems = preview.items().stream()
            .filter(item -> !item.commentable())
            .map(item -> new GithubCommentPublishItem(
                item.findingId(),
                item.file(),
                item.line(),
                item.targetType(),
                Boolean.TRUE.equals(item.published()),
                Boolean.TRUE.equals(item.published()) ? "already_published" : "skipped",
                Boolean.TRUE.equals(item.published()) ? "GitHub comment already published" : item.reason(),
                null,
                null,
                null,
                item.publicationUrl(),
                null,
                item.publishedAt()
            ))
            .toList();

        List<GithubReviewCommentDraft> drafts = preview.items().stream()
            .filter(GithubCommentPreviewItem::commentable)
            .map(item -> new GithubReviewCommentDraft(item.findingId(), item.file(), item.line(), item.commentBody(), item.targetType()))
            .toList();

        List<GithubCommentPublishItem> publishedItems = publishDrafts(task, drafts);
        List<GithubCommentPublishItem> items = new java.util.ArrayList<>(publishedItems);
        items.addAll(skippedItems);

        int succeededCount = (int) publishedItems.stream().filter(GithubCommentPublishItem::success).count();
        int failedCount = publishedItems.size() - succeededCount;
        recordGithubCommentPublishMetrics(succeededCount, failedCount, skippedItems.size());
        GithubCommentPublishResponse response = new GithubCommentPublishResponse(
            task.getId(),
            preview.totalFindings(),
            drafts.size(),
            succeededCount,
            failedCount,
            skippedItems.size(),
            items
        );
        // 幂等表只保留审查发现当前发布状态；批次表保留本次点击回写按钮的完整审计轨迹。
        Long batchId = savePublicationBatch(response);
        publishGithubCommentNotification(task, response, batchId);
        recordGithubCommentPublishDuration(startedAt, failedCount > 0 ? "failed" : "success");
        return response;
    }

    private void recordGithubCommentPublishDuration(LocalDateTime startedAt, String result) {
        if (metrics != null) {
            metrics.githubCommentPublishDuration(Duration.between(startedAt, LocalDateTime.now()), result);
        }
    }

    private void recordGithubCommentPublishMetrics(int succeededCount, int failedCount, int skippedCount) {
        if (metrics == null) {
            return;
        }
        for (int i = 0; i < succeededCount; i++) {
            metrics.githubCommentPublished("success");
        }
        for (int i = 0; i < failedCount; i++) {
            metrics.githubCommentPublished("failed");
        }
        for (int i = 0; i < skippedCount; i++) {
            metrics.githubCommentPublished("skipped");
        }
    }

    @Override
    public ReviewTaskDetail getReviewDetail(Long id) {
        return reviewTaskQueryService.getReviewDetail(id);
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        return reviewTaskQueryService.getReviewStatus(id);
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(Long id) {
        return githubCommentApplicationService.getGithubCommentPreview(id);
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id, int page, int pageSize, String status) {
        return githubCommentApplicationService.getGithubCommentPublicationHistory(id, page, pageSize, status);
    }

    @Override
    @Transactional
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        return reviewTaskCommandService.triggerManualReview(request);
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }

    @Override
    @Transactional
    public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        if (!Boolean.TRUE.equals(task.getHumanReviewRequired())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Human review is not required for this task");
        }
        if (!HUMAN_REVIEW_PENDING.equals(resolveHumanReviewStatus(task))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Human review has already been decided");
        }

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
    public FindingFeedbackResponse updateFindingFeedback(Long id, Long findingId, FindingFeedbackRequest request, String operator) {
        return findingFeedbackService.updateFindingFeedback(id, findingId, request, operator);
    }

    @Override
    @Transactional
    public ReviewRetryResponse retryReview(Long id) {
        return reviewTaskCommandService.retryReview(id);
    }

    @Override
    public GithubPullRequestOptionsResponse listConfiguredGithubPullRequests() {
        GithubRepositoryRef repositoryRef = githubPullRequestClient.getConfiguredRepository();
        List<GithubPullRequestSummary> pullRequests = githubPullRequestClient.listOpenPullRequests();
        return new GithubPullRequestOptionsResponse(
            repositoryRef.owner(),
            repositoryRef.repository(),
            pullRequests.stream()
                .map(item -> new GithubPullRequestOption(
                    item.number(),
                    item.title(),
                    item.branch(),
                    item.commit(),
                    item.commit(),
                    item.author(),
                    item.url(),
                    item.updatedAt()
                ))
                .toList()
        );
    }

    private List<GithubCommentPublishItem> publishDrafts(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        try {
            return githubPullRequestClient.publishPullRequestComments(task, drafts).stream()
                .map(result -> toGithubCommentPublishItem(task.getId(), result))
                .toList();
        } catch (RuntimeException ex) {
            String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            return drafts.stream()
                .map(draft -> {
                    GithubReviewCommentResult result = new GithubReviewCommentResult(
                        draft.findingId(),
                        draft.path(),
                        draft.line(),
                        draft.targetType(),
                        false,
                        "failed",
                        message,
                        null,
                        null
                    );
                    return toGithubCommentPublishItem(task.getId(), result);
                })
                .toList();
        }
    }

    private GithubCommentPublishItem toGithubCommentPublishItem(Long taskId, GithubReviewCommentResult result) {
        GithubCommentPublication publication = savePublication(taskId, result);
        FailureSummary failureSummary = resolveGithubWritebackFailure(result.status(), result.success(), result.message());
        return new GithubCommentPublishItem(
            result.findingId(),
            result.path(),
            result.line(),
            result.targetType(),
            result.success(),
            result.status(),
            result.message(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            result.url(),
            result.commentId(),
            publication.getPublishedAt() == null ? null : publication.getPublishedAt().format(DATE_TIME_FORMATTER)
        );
    }

    private Long savePublicationBatch(GithubCommentPublishResponse response) {
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setTaskId(response.taskId());
        batch.setStatus(resolvePublicationBatchStatus(response));
        batch.setTotalFindings(response.totalFindings());
        batch.setAttemptedCount(response.attemptedCount());
        batch.setSucceededCount(response.succeededCount());
        batch.setFailedCount(response.failedCount());
        batch.setSkippedCount(response.skippedCount());
        batch.setCreatedAt(now);
        batch.setCompletedAt(now);
        githubCommentPublicationBatchMapper.insert(batch);

        // 所有结果都写入批次明细，包括 already_published 和 skipped，避免历史视图丢上下文。
        for (GithubCommentPublishItem item : response.items()) {
            GithubCommentPublicationBatchItem historyItem = new GithubCommentPublicationBatchItem();
            historyItem.setBatchId(batch.getId());
            historyItem.setTaskId(response.taskId());
            historyItem.setFindingId(item.findingId());
            historyItem.setFilePath(item.file());
            historyItem.setLineNumber(item.line());
            historyItem.setTargetType(item.targetType());
            historyItem.setStatus(item.status());
            historyItem.setSuccess(item.success());
            historyItem.setGithubCommentId(item.githubCommentId());
            historyItem.setGithubUrl(item.url());
            historyItem.setMessage(item.message());
            historyItem.setPublishedAt(parseDateTimeOrNull(item.publishedAt()));
            historyItem.setCreatedAt(now);
            githubCommentPublicationBatchItemMapper.insert(historyItem);
        }
        return batch.getId();
    }

    private void publishGithubCommentNotification(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        if (notificationDispatchService != null) {
            notificationDispatchService.githubCommentsPublished(task, response, batchId);
        }
    }

    private String resolvePublicationBatchStatus(GithubCommentPublishResponse response) {
        // 批次状态用于页面摘要，不替代逐条审查发现的精确状态。
        if (response.totalFindings() == 0) {
            return "empty";
        }
        if (response.failedCount() > 0) {
            return response.succeededCount() > 0 ? "partial_failed" : "failed";
        }
        if (response.attemptedCount() == 0 && response.skippedCount() > 0) {
            return "skipped";
        }
        return "completed";
    }

    private GithubCommentPublication loadPrSummaryPublication(Long taskId) {
        return githubCommentPublicationMapper.selectOne(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .isNull(GithubCommentPublication::getFindingId)
                .eq(GithubCommentPublication::getTargetType, "pull_request")
                .last("limit 1")
        );
    }

    private GithubCommentPublication savePublication(Long taskId, GithubReviewCommentResult result) {
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublication publication = result.findingId() == null
            ? loadPrSummaryPublication(taskId)
            : githubCommentPublicationMapper.selectOne(
                new LambdaQueryWrapper<GithubCommentPublication>()
                    .eq(GithubCommentPublication::getFindingId, result.findingId())
                    .last("limit 1")
            );
        boolean existing = publication != null;
        if (!existing) {
            publication = new GithubCommentPublication();
            publication.setTaskId(taskId);
            publication.setFindingId(result.findingId());
            publication.setCreatedAt(now);
        }
        publication.setTargetType(result.targetType());
        publication.setStatus(result.status());
        publication.setSuccess(result.success());
        publication.setGithubCommentId(result.commentId());
        publication.setGithubUrl(result.url());
        publication.setMessage(result.message());
        publication.setPublishedAt(Boolean.TRUE.equals(result.success()) ? now : null);
        publication.setUpdatedAt(now);
        if (existing) {
            githubCommentPublicationMapper.updateById(publication);
        } else {
            githubCommentPublicationMapper.insert(publication);
        }
        return publication;
    }

    private FailureSummary resolveGithubWritebackFailure(String status, Boolean success, String message) {
        if (Boolean.TRUE.equals(success) || !"failed".equalsIgnoreCase(status)) {
            return NO_FAILURE_SUMMARY;
        }
        return classifyGithubWritebackFailure(message);
    }

    private FailureSummary classifyGithubWritebackFailure(String message) {
        // 回写失败只持久化原始 message，这里即时派生中文提示，避免为展示字段新增数据库列。
        String normalized = StringUtils.hasText(message) ? message.trim() : "";
        String lowerMessage = normalized.toLowerCase(Locale.ROOT);

        if (lowerMessage.contains("category=github_token_invalid")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 鏃犳晥鎴栧凡杩囨湡",
                "璇峰埌闆嗘垚閰嶇疆椤垫洿鏂?GitHub Token锛岀‘璁よ繛鎺ユ祴璇曢€氳繃鍚庨噸鏂板洖鍐欍€?"
            );
        }
        if (lowerMessage.contains("category=github_permission_denied")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 鏉冮檺涓嶈冻",
                "璇风‘璁?Token 瀵圭洰鏍囦粨搴撳叿澶?Pull Request/Issue 璇勮鏉冮檺鍚庨噸鏂板洖鍐欍€?"
            );
        }
        if (lowerMessage.contains("category=github_target_not_found")) {
            return new FailureSummary(
                "github_target_not_found",
                "GitHub PR 鎴栦粨搴撲笉鍙闂?",
                "璇风‘璁や换鍔′粨搴撱€丳R 缂栧彿鍜?Token 鍙闂寖鍥达紝鍐嶉噸鏂板洖鍐欒瘎璁恒€?"
            );
        }
        if (lowerMessage.contains("category=github_rate_limited")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 璁块棶鍙楅檺",
                "璇风◢鍚庨噸璇曪紝鎴栨洿鎹㈠墿浣欓搴﹀厖瓒崇殑 GitHub Token銆?"
            );
        }
        if (lowerMessage.contains("category=github_timeout")) {
            return new FailureSummary(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("category=github_service_unavailable")) {
            return new FailureSummary(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerMessage.contains("token is not configured")) {
            return new FailureSummary(
                "github_token_missing",
                "GitHub Token 未配置",
                "请到集成配置页保存 GitHub Token 后重新回写评论。"
            );
        }
        if (lowerMessage.contains("401") || lowerMessage.contains("bad credentials")
            || lowerMessage.contains("unauthorized") || lowerMessage.contains("requires authentication")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认连接测试通过后重新回写。"
            );
        }
        if (lowerMessage.contains("403") || lowerMessage.contains("forbidden")
            || lowerMessage.contains("resource not accessible") || lowerMessage.contains("permission")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库具备 Pull Request/Issue 评论权限后重新回写。"
            );
        }
        if (lowerMessage.contains("404") || lowerMessage.contains("not found")) {
            return new FailureSummary(
                "github_target_not_found",
                "GitHub PR 或仓库不可访问",
                "请确认任务仓库、PR 编号和 Token 可访问范围，再重新回写评论。"
            );
        }
        if (isGithubCommentPositionFailure(lowerMessage)) {
            return new FailureSummary(
                "github_comment_position_invalid",
                "GitHub 行评论定位失败",
                "请检查该审查发现是否仍在 PR Diff 中；必要时改为 PR 总评评论。"
            );
        }
        if (lowerMessage.contains("rate limit")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return new FailureSummary(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("owner or repository is not configured")) {
            return new FailureSummary(
                "github_repository_not_configured",
                "GitHub 仓库未配置",
                "请在集成配置中补全默认仓库，或确认任务携带了正确仓库信息。"
            );
        }
        return new FailureSummary(
            "github_writeback_failed",
            "GitHub 评论回写失败",
            "请查看原始错误信息，确认 GitHub 集成配置和目标 PR 状态后重试。"
        );
    }

    private boolean isGithubCommentPositionFailure(String lowerMessage) {
        return lowerMessage.contains("422")
            || lowerMessage.contains("validation failed")
            || lowerMessage.contains("position")
            || lowerMessage.contains("commit_id")
            || lowerMessage.contains("line must")
            || lowerMessage.contains("line is")
            || lowerMessage.contains("line does not")
            || lowerMessage.contains("not part of the diff")
            || lowerMessage.contains("diff hunk");
    }

    private LocalDateTime parseDateTimeOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private LocalDateTime resolveTaskUpdatedAt(ReviewTask task) {
        if (task.getFinishedAt() != null) {
            return task.getFinishedAt();
        }
        if (task.getStartedAt() != null) {
            return task.getStartedAt();
        }
        return task.getCreatedAt();
    }

    private ReviewTimelineItem toTimelineItem(ReviewTimeline timeline) {
        return new ReviewTimelineItem(
            timeline.getLabel(),
            timeline.getEventTime().format(TIME_FORMATTER),
            switch (timeline.getStatus()) {
                case "DONE" -> "done";
                case "CURRENT" -> "current";
                case "FAILED" -> "done";
                default -> "pending";
            }
        );
    }

    private void ensureGithubCommentPublishAllowed(ReviewTask task) {
        if (!Boolean.TRUE.equals(task.getHumanReviewRequired())) {
            return;
        }
        String humanReviewStatus = resolveHumanReviewStatus(task);
        if (HUMAN_REVIEW_APPROVED.equals(humanReviewStatus) || HUMAN_REVIEW_CHANGES_REQUESTED.equals(humanReviewStatus)) {
            return;
        }
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Human review approval or changes request is required before publishing GitHub comments"
        );
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
        return switch (action) {
            case "APPROVE" -> HUMAN_REVIEW_APPROVED;
            case "CHANGES_REQUESTED" -> HUMAN_REVIEW_CHANGES_REQUESTED;
            case "REJECT" -> HUMAN_REVIEW_REJECTED;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported human review action: " + action);
        };
    }

    private String taskStatusForHumanReview(String humanReviewStatus) {
        return switch (humanReviewStatus) {
            case HUMAN_REVIEW_APPROVED -> STATUS_APPROVED;
            case HUMAN_REVIEW_CHANGES_REQUESTED -> STATUS_CHANGES_REQUESTED;
            case HUMAN_REVIEW_REJECTED -> STATUS_REJECTED;
            default -> STATUS_PENDING_HUMAN_REVIEW;
        };
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (!Boolean.TRUE.equals(task.getHumanReviewRequired())) {
            return HUMAN_REVIEW_NOT_REQUIRED;
        }
        return StringUtils.hasText(task.getHumanReviewStatus()) ? task.getHumanReviewStatus() : HUMAN_REVIEW_PENDING;
    }

    private String humanReviewTimelineLabel(String humanReviewStatus, String note) {
        String base = switch (humanReviewStatus) {
            case HUMAN_REVIEW_APPROVED -> "Human review approved";
            case HUMAN_REVIEW_CHANGES_REQUESTED -> "Human review requested changes";
            case HUMAN_REVIEW_REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
        return StringUtils.hasText(note) ? truncate(base + ": " + note) : base;
    }

    private String humanReviewMessage(String humanReviewStatus) {
        return switch (humanReviewStatus) {
            case HUMAN_REVIEW_APPROVED -> "Human review approved";
            case HUMAN_REVIEW_CHANGES_REQUESTED -> "Human review requested changes";
            case HUMAN_REVIEW_REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
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

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
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

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private record FailureSummary(String category, String reason, String suggestion) {
    }
}
