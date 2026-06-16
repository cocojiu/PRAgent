package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryItem;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubCommentWritebackCheck;
import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewFinding;
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
import com.repoguard.agent.service.ReviewService;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewTaskQueryService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReviewServiceImpl implements ReviewService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final String SOURCE_MANUAL_INPUT = "MANUAL_INPUT";
    private static final String STATUS_PENDING_HUMAN_REVIEW = "PENDING_HUMAN_REVIEW";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_CHANGES_REQUESTED = "CHANGES_REQUESTED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String HUMAN_REVIEW_PENDING = "PENDING";
    private static final String HUMAN_REVIEW_APPROVED = "APPROVED";
    private static final String HUMAN_REVIEW_CHANGES_REQUESTED = "CHANGES_REQUESTED";
    private static final String HUMAN_REVIEW_REJECTED = "REJECTED";
    private static final String HUMAN_REVIEW_NOT_REQUIRED = "NOT_REQUIRED";
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";
    private static final String FEEDBACK_VALID = "VALID";
    private static final String FEEDBACK_FALSE_POSITIVE = "FALSE_POSITIVE";
    private static final String FEEDBACK_FIXED = "FIXED";
    private static final String FEEDBACK_IGNORED = "IGNORED";
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
        FindingFeedbackService findingFeedbackService
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
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        Map<String, ChangedFile> changedFileByPath = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, id)
                .orderByAsc(ChangedFile::getId)
        ).stream().collect(Collectors.toMap(
            ChangedFile::getFilePath,
            Function.identity(),
            (first, ignored) -> first
        ));

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, id)
                .orderByAsc(ReviewFinding::getId)
        );
        List<ReviewFinding> actionableFindings = findings.stream()
            .filter(finding -> "FINDING".equals(finding.getCategory()))
            .toList();
        List<ReviewFindingDto> findingDtos = actionableFindings.stream().map(this::toFindingDto).toList();
        List<MissingTestDto> missingTests = findings.stream()
            .filter(finding -> "MISSING_TEST".equals(finding.getCategory()))
            .map(this::toMissingTestDto)
            .toList();
        List<ChangedFileDto> changedFileDtos = changedFileByPath.values().stream()
            .sorted(Comparator.comparing(file -> file.getId() == null ? Long.MAX_VALUE : file.getId()))
            .map(this::toChangedFileDto)
            .toList();
        ReviewTaskListItem taskItem = toListItem(task, resolveFailureSummary(task, List.of()));
        PrRiskProfileDto riskProfile = buildRiskProfile(taskItem, findingDtos, changedFileDtos);
        PrReviewSummaryDto prSummary = buildPrReviewSummary(taskItem, findingDtos, missingTests, changedFileDtos, riskProfile);
        Map<Long, GithubCommentPublication> publicationByFindingId = loadPublicationByFindingId(id, actionableFindings);
        GithubCommentPublication prSummaryPublication = loadPrSummaryPublication(id);

        List<GithubCommentPreviewItem> items = new java.util.ArrayList<>();
        items.add(toPrSummaryCommentPreviewItem(prSummary, prSummaryPublication));
        items.addAll(actionableFindings.stream()
            .map(finding -> toGithubCommentPreviewItem(
                finding,
                changedFileByPath.get(finding.getFilePath()),
                publicationByFindingId.get(finding.getId())
            ))
            .toList());

        int commentableCount = (int) items.stream().filter(GithubCommentPreviewItem::commentable).count();
        int publishedCount = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.published())).count();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            buildGithubCommentWritebackCheck(task),
            actionableFindings.size(),
            commentableCount,
            items.size() - commentableCount - publishedCount,
            items
        );
    }

    private GithubCommentWritebackCheck buildGithubCommentWritebackCheck(ReviewTask task) {
        IntegrationConfig config = loadGithubIntegrationConfig();
        String taskOwner = trimToNull(task.getOrganization());
        String taskRepository = trimToNull(task.getRepository());
        String configuredOwner = trimToNull(config == null ? null : config.getDefaultOwner());
        String configuredRepository = trimToNull(config == null ? null : config.getDefaultRepo());
        boolean tokenConfigured = config != null && StringUtils.hasText(config.getTokenValue());
        boolean repositoryConfigured = StringUtils.hasText(configuredOwner) && StringUtils.hasText(configuredRepository);
        boolean repositoryMatched = repositoryConfigured
            && equalsIgnoreCase(taskOwner, configuredOwner)
            && equalsIgnoreCase(taskRepository, configuredRepository);
        boolean connectionHealthy = tokenConfigured
            && repositoryConfigured
            && repositoryMatched;

        List<String> messages = new java.util.ArrayList<>();
        if (!tokenConfigured) {
            messages.add("GitHub Token 未配置，请先到集成配置页保存 Token。");
        }
        if (!repositoryConfigured) {
            messages.add("GitHub 默认 owner/repo 未配置，无法提前判断任务仓库是否匹配。");
        } else if (!repositoryMatched) {
            messages.add("当前任务仓库与 GitHub 集成默认仓库不一致，请确认 Token 对目标仓库有评论权限。");
        }
        if (config != null && StringUtils.hasText(config.getLastError())) {
            messages.add("GitHub 最近一次连接测试失败：" + config.getLastError());
        } else if (config != null && !"CONFIGURED".equals(config.getStatus())) {
            messages.add("GitHub 当前连接状态不是已配置成功，请先到集成配置页测试连接。");
        }
        if (messages.isEmpty()) {
            messages.add("GitHub 回写配置与当前任务仓库匹配。");
        }

        String status = resolveWritebackCheckStatus(tokenConfigured, repositoryConfigured, repositoryMatched, connectionHealthy);
        return new GithubCommentWritebackCheck(
            status,
            resolveWritebackCheckLevel(status),
            taskOwner,
            taskRepository,
            configuredOwner,
            configuredRepository,
            repositoryMatched,
            tokenConfigured,
            connectionHealthy,
            config == null ? null : config.getLastError(),
            messages
        );
    }

    private IntegrationConfig loadGithubIntegrationConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private String resolveWritebackCheckStatus(
        boolean tokenConfigured,
        boolean repositoryConfigured,
        boolean repositoryMatched,
        boolean connectionHealthy
    ) {
        if (!tokenConfigured) {
            return "token_missing";
        }
        if (!repositoryConfigured) {
            return "repository_not_configured";
        }
        if (!repositoryMatched) {
            return "repository_mismatch";
        }
        if (!connectionHealthy) {
            return "connection_failed";
        }
        return "ready";
    }

    private String resolveWritebackCheckLevel(String status) {
        return switch (status) {
            case "ready" -> "success";
            case "repository_mismatch", "repository_not_configured" -> "warning";
            default -> "danger";
        };
    }

    private String normalizeOptionalStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id, int page, int pageSize, String status) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        // 批次倒序展示，用户先看到最近一次回写结果。
        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<GithubCommentPublicationBatch> batchQuery = new LambdaQueryWrapper<GithubCommentPublicationBatch>()
            .eq(GithubCommentPublicationBatch::getTaskId, id)
            .eq(normalizedStatus != null, GithubCommentPublicationBatch::getStatus, normalizedStatus)
            .orderByDesc(GithubCommentPublicationBatch::getCreatedAt)
            .orderByDesc(GithubCommentPublicationBatch::getId);
        Page<GithubCommentPublicationBatch> batchPage = githubCommentPublicationBatchMapper.selectPage(
            Page.of(page, pageSize),
            batchQuery
        );
        List<GithubCommentPublicationBatch> batches = batchPage.getRecords();
        if (batches == null || batches.isEmpty()) {
            return new GithubCommentPublicationHistoryResponse(
                task.getId(),
                batchPage.getTotal(),
                page,
                pageSize,
                normalizedStatus,
                List.of()
            );
        }

        List<Long> batchIds = batches.stream().map(GithubCommentPublicationBatch::getId).toList();
        Map<Long, List<GithubCommentPublicationBatchItem>> itemsByBatchId = githubCommentPublicationBatchItemMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublicationBatchItem>()
                .in(GithubCommentPublicationBatchItem::getBatchId, batchIds)
                .orderByAsc(GithubCommentPublicationBatchItem::getId)
        ).stream().collect(Collectors.groupingBy(GithubCommentPublicationBatchItem::getBatchId));

        List<GithubCommentPublicationBatchDto> batchDtos = batches.stream()
            .map(batch -> toGithubCommentPublicationBatchDto(batch, itemsByBatchId.getOrDefault(batch.getId(), List.of())))
            .toList();
        return new GithubCommentPublicationHistoryResponse(
            task.getId(),
            batchPage.getTotal(),
            page,
            pageSize,
            normalizedStatus,
            batchDtos
        );
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

    private PrReviewSummaryDto buildPrReviewSummary(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        PrRiskProfileDto riskProfile
    ) {
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        int totalFindings = findings.size();
        String overallRisk = riskProfile == null ? "info" : riskProfile.level();
        boolean humanReviewRequired = Boolean.TRUE.equals(task.humanReviewRequired())
            || Boolean.TRUE.equals(riskProfile == null ? false : riskProfile.recommendHumanReview());
        boolean recommendMerge = criticalCount == 0 && highCount == 0 && !humanReviewRequired;
        String mergeRecommendation = mergeRecommendation(recommendMerge, criticalCount, highCount, mediumCount, humanReviewRequired);
        List<String> keyRisks = buildPrSummaryRisks(riskProfile, missingTests, criticalCount, highCount, mediumCount);
        List<String> focusFiles = buildPrSummaryFocusFiles(riskProfile, changedFiles);
        String summary = "本次 PR 综合风险为 " + riskText(overallRisk)
            + "，包含 " + changedFiles.size() + " 个变更文件、" + totalFindings + " 条审查发现"
            + (missingTests.isEmpty() ? "" : "、" + missingTests.size() + " 条缺失测试建议")
            + "。";
        String commentBody = buildPrSummaryCommentBody(task, overallRisk, summary, mergeRecommendation, keyRisks, focusFiles);
        return new PrReviewSummaryDto(
            overallRisk,
            summary,
            mergeRecommendation,
            recommendMerge,
            humanReviewRequired,
            keyRisks,
            focusFiles,
            commentBody
        );
    }

    private String mergeRecommendation(
        boolean recommendMerge,
        int criticalCount,
        int highCount,
        int mediumCount,
        boolean humanReviewRequired
    ) {
        if (criticalCount > 0 || highCount > 0) {
            return "暂不建议直接合并，请优先处理高风险发现后再评估。";
        }
        if (humanReviewRequired || mediumCount > 0) {
            return "建议完成必要人工复核和中风险确认后再合并。";
        }
        return recommendMerge ? "未发现阻塞性风险，可按团队流程合并。" : "建议完成复核后再合并。";
    }

    private List<String> buildPrSummaryRisks(
        PrRiskProfileDto riskProfile,
        List<MissingTestDto> missingTests,
        int criticalCount,
        int highCount,
        int mediumCount
    ) {
        List<String> risks = new java.util.ArrayList<>();
        if (criticalCount > 0) {
            risks.add("包含 " + criticalCount + " 条严重风险发现");
        }
        if (highCount > 0) {
            risks.add("包含 " + highCount + " 条高风险发现");
        }
        if (mediumCount > 0) {
            risks.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (!missingTests.isEmpty()) {
            risks.add("存在 " + missingTests.size() + " 条缺失测试建议");
        }
        if (riskProfile != null && riskProfile.signals() != null) {
            riskProfile.signals().stream()
                .filter(StringUtils::hasText)
                .filter(signal -> risks.stream().noneMatch(existing -> existing.equals(signal)))
                .limit(Math.max(0, 5 - risks.size()))
                .forEach(risks::add);
        }
        if (risks.isEmpty()) {
            risks.add("未发现明显阻塞性风险");
        }
        return risks.stream().limit(5).toList();
    }

    private List<String> buildPrSummaryFocusFiles(PrRiskProfileDto riskProfile, List<ChangedFileDto> changedFiles) {
        List<String> files = new java.util.ArrayList<>();
        if (riskProfile != null && riskProfile.highRiskFiles() != null) {
            riskProfile.highRiskFiles().stream()
                .map(PrRiskFileDto::file)
                .filter(StringUtils::hasText)
                .limit(3)
                .forEach(files::add);
        }
        if (files.size() < 3) {
            changedFiles.stream()
                .sorted(Comparator.comparingInt(file -> -(safeInt(file.additions()) + safeInt(file.deletions()))))
                .map(ChangedFileDto::path)
                .filter(StringUtils::hasText)
                .filter(file -> !files.contains(file))
                .limit(3 - files.size())
                .forEach(files::add);
        }
        return files;
    }

    private String buildPrSummaryCommentBody(
        ReviewTaskListItem task,
        String overallRisk,
        String summary,
        String mergeRecommendation,
        List<String> keyRisks,
        List<String> focusFiles
    ) {
        StringBuilder body = new StringBuilder();
        body.append("## RepoGuard PR 总评");
        body.append("\n\n").append(summary);
        body.append("\n\n**合并建议**：").append(mergeRecommendation);
        body.append("\n\n**关键风险**");
        keyRisks.forEach(risk -> body.append("\n- ").append(risk));
        if (!focusFiles.isEmpty()) {
            body.append("\n\n**建议重点查看文件**");
            focusFiles.forEach(file -> body.append("\n- `").append(file).append("`"));
        }
        body.append("\n\n> 任务 #").append(task.id()).append("，风险等级：").append(riskText(overallRisk)).append("。");
        return body.toString();
    }

    private String riskText(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return "提示";
        }
        return switch (riskLevel.trim().toLowerCase(Locale.ROOT)) {
            case "critical" -> "严重";
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            default -> "提示";
        };
    }

    private PrRiskProfileDto buildRiskProfile(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<ChangedFileDto> changedFiles
    ) {
        Map<String, Long> findingCountByFile = findings.stream()
            .filter(finding -> StringUtils.hasText(finding.file()))
            .collect(Collectors.groupingBy(ReviewFindingDto::file, Collectors.counting()));
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        int lowCount = countSeverity(findings, "low");
        int totalChurn = changedFiles.stream()
            .mapToInt(file -> safeInt(file.additions()) + safeInt(file.deletions()))
            .sum();
        int sensitiveFileCount = (int) changedFiles.stream().filter(file -> !riskReasons(file).isEmpty()).count();

        int score = criticalCount * 35
            + highCount * 25
            + mediumCount * 12
            + lowCount * 4
            + Math.min(changedFiles.size() * 2, 20)
            + Math.min(totalChurn / 50, 20)
            + Math.min(sensitiveFileCount * 8, 24);
        score = Math.min(score, 100);
        String level = scoreToRiskLevel(score, task.riskLevel());

        List<String> signals = new java.util.ArrayList<>();
        if (criticalCount + highCount > 0) {
            signals.add("包含 " + (criticalCount + highCount) + " 条高危以上发现");
        }
        if (mediumCount > 0) {
            signals.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (changedFiles.size() >= 8) {
            signals.add("变更文件较多：" + changedFiles.size() + " 个文件");
        }
        if (totalChurn >= 300) {
            signals.add("变更规模较大：" + totalChurn + " 行增删");
        }
        if (sensitiveFileCount > 0) {
            signals.add("触及 " + sensitiveFileCount + " 个敏感文件");
        }
        if (signals.isEmpty()) {
            signals.add("未发现明显放大风险的变更信号");
        }

        boolean recommendHumanReview = score >= 55 || Boolean.TRUE.equals(task.humanReviewRequired());
        String humanReviewReason = recommendHumanReview
            ? "风险分达到 " + score + "，建议人工复核后再回写或合并。"
            : "风险分较低，可按常规自动审查流程推进。";

        List<PrRiskFileDto> highRiskFiles = changedFiles.stream()
            .map(file -> toRiskFile(file, findingCountByFile.getOrDefault(file.path(), 0L).intValue()))
            .filter(file -> file.score() > 0)
            .sorted(Comparator.comparing(PrRiskFileDto::score).reversed())
            .limit(5)
            .toList();

        return new PrRiskProfileDto(
            score,
            level,
            buildRiskSummary(level, score, findings.size(), changedFiles.size(), totalChurn),
            recommendHumanReview,
            humanReviewReason,
            signals,
            highRiskFiles
        );
    }

    private PrRiskFileDto toRiskFile(ChangedFileDto file, int findingCount) {
        List<String> reasons = riskReasons(file);
        int churn = safeInt(file.additions()) + safeInt(file.deletions());
        int score = findingCount * 18 + Math.min(churn / 25, 20) + reasons.size() * 12;
        return new PrRiskFileDto(
            file.path(),
            file.changeType(),
            file.additions(),
            file.deletions(),
            findingCount,
            Math.min(score, 100),
            reasons
        );
    }

    private List<String> riskReasons(ChangedFileDto file) {
        String path = file.path() == null ? "" : file.path().toLowerCase(Locale.ROOT);
        List<String> reasons = new java.util.ArrayList<>();
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            reasons.add("数据库迁移");
        }
        if (path.contains("security") || path.contains("auth") || path.contains("token") || path.contains("permission")) {
            reasons.add("认证或权限");
        }
        if (path.endsWith("application.yml") || path.endsWith("application-prod.yml") || path.contains("config")) {
            reasons.add("运行配置");
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            reasons.add("构建或发布链路");
        }
        return reasons;
    }

    private int countSeverity(List<ReviewFindingDto> findings, String severity) {
        return (int) findings.stream().filter(finding -> severity.equalsIgnoreCase(finding.severity())).count();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String scoreToRiskLevel(int score, String fallbackRiskLevel) {
        if (score >= 80 || "critical".equalsIgnoreCase(fallbackRiskLevel)) {
            return "critical";
        }
        if (score >= 55 || "high".equalsIgnoreCase(fallbackRiskLevel)) {
            return "high";
        }
        if (score >= 30 || "medium".equalsIgnoreCase(fallbackRiskLevel)) {
            return "medium";
        }
        if (score >= 10 || "low".equalsIgnoreCase(fallbackRiskLevel)) {
            return "low";
        }
        return "info";
    }

    private String buildRiskSummary(String level, int score, int findingCount, int fileCount, int totalChurn) {
        return "本次 PR 综合风险为 " + lower(level)
            + "（" + score + "/100），覆盖 "
            + fileCount + " 个变更文件、" + totalChurn + " 行增删，审查发现 "
            + findingCount + " 条。";
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

    private GithubCommentPublicationBatchDto toGithubCommentPublicationBatchDto(
        GithubCommentPublicationBatch batch,
        List<GithubCommentPublicationBatchItem> items
    ) {
        return new GithubCommentPublicationBatchDto(
            batch.getId(),
            batch.getStatus(),
            batch.getTotalFindings(),
            batch.getAttemptedCount(),
            batch.getSucceededCount(),
            batch.getFailedCount(),
            batch.getSkippedCount(),
            formatDateTimeOrNull(batch.getCreatedAt()),
            formatDateTimeOrNull(batch.getCompletedAt()),
            items.stream().map(this::toGithubCommentPublicationHistoryItem).toList()
        );
    }

    private GithubCommentPublicationHistoryItem toGithubCommentPublicationHistoryItem(GithubCommentPublicationBatchItem item) {
        FailureSummary failureSummary = resolveGithubWritebackFailure(item.getStatus(), item.getSuccess(), item.getMessage());
        return new GithubCommentPublicationHistoryItem(
            item.getFindingId(),
            item.getFilePath(),
            item.getLineNumber(),
            item.getTargetType(),
            item.getSuccess(),
            item.getStatus(),
            item.getMessage(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            item.getGithubUrl(),
            item.getGithubCommentId(),
            formatDateTimeOrNull(item.getPublishedAt())
        );
    }

    private Map<Long, GithubCommentPublication> loadPublicationByFindingId(Long taskId, List<ReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> findingIds = findings.stream().map(ReviewFinding::getId).toList();
        List<GithubCommentPublication> publications = githubCommentPublicationMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .in(GithubCommentPublication::getFindingId, findingIds)
        );
        if (publications == null || publications.isEmpty()) {
            return Collections.emptyMap();
        }
        return publications.stream().collect(Collectors.toMap(
            GithubCommentPublication::getFindingId,
            Function.identity(),
            (first, ignored) -> first
        ));
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

    private ReviewTaskListItem toListItem(ReviewTask task, FailureSummary failureSummary) {
        return new ReviewTaskListItem(
            task.getId(),
            task.getPrNumber(),
            task.getTitle(),
            task.getRepository(),
            task.getOrganization(),
            task.getCommitSha(),
            task.getBranchName(),
            lower(task.getStatus()),
            lower(task.getRiskLevel()),
            task.getMqRetries(),
            lower(task.getLlmStatus()),
            lower(resolveStoredSource(task.getSource())),
            lower(resolveStoredSource(task.getTriggerSource())),
            task.getCreatedAt().format(DATE_TIME_FORMATTER),
            formatDuration(task.getDurationSeconds()),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt())
        );
    }

    private FailureSummary resolveFailureSummary(ReviewTask task, List<String> timelineLabels) {
        if (!"FAILED".equals(task.getStatus())) {
            return NO_FAILURE_SUMMARY;
        }

        String detail = timelineLabels.stream()
            .filter(StringUtils::hasText)
            .filter(label -> label.equals("Review failed") || label.startsWith("Review failed:"))
            .reduce((first, second) -> second)
            .map(this::extractFailureDetail)
            .orElse("");
        return classifyFailure(detail);
    }

    private String extractFailureDetail(String label) {
        if (label.startsWith("Review failed:")) {
            return label.replaceFirst("Review failed:", "").trim();
        }
        return "";
    }

    private FailureSummary classifyFailure(String detail) {
        // 失败原因来自执行时间线，统一在服务层归类，避免前端重复解析英文日志标签。
        String normalized = StringUtils.hasText(detail) ? detail.trim() : "";
        String lowerDetail = normalized.toLowerCase(Locale.ROOT);

        if (lowerDetail.contains("category=github_token_invalid")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 鏃犳晥鎴栧凡杩囨湡",
                "璇峰埌闆嗘垚閰嶇疆椤垫洿鏂?GitHub Token锛岀‘璁や繚瀛樻垚鍔熷悗鍐嶉噸璇曞鏌ャ€?"
            );
        }
        if (lowerDetail.contains("category=github_permission_denied")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 鏉冮檺涓嶈冻",
                "璇风‘璁?Token 瀵圭洰鏍囦粨搴撳拰 PR 鍏峰璇诲彇鏉冮檺锛屽繀瑕佹椂琛ュ厖 repo 鏉冮檺鍚庨噸璇曘€?"
            );
        }
        if (lowerDetail.contains("category=github_target_not_found")) {
            return new FailureSummary(
                "github_target_not_found",
                "PR 鎴栦粨搴撲笉瀛樺湪/涓嶅彲璁块棶",
                "璇风‘璁や粨搴撳悕绉般€佺粍缁囥€丳R 缂栧彿鍜?Token 鍙闂寖鍥达紝鐒跺悗閲嶆柊瑙﹀彂瀹℃煡銆?"
            );
        }
        if (lowerDetail.contains("category=github_rate_limited")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 璁块棶鍙楅檺",
                "璇风◢鍚庨噸璇曪紝鎴栨洿鎹㈠墿浣欓搴﹀厖瓒崇殑 GitHub Token銆?"
            );
        }
        if (lowerDetail.contains("category=github_service_unavailable")) {
            return new FailureSummary(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerDetail.contains("category=github_timeout")) {
            return new FailureSummary(
                "github_timeout",
                "GitHub API 响应超时",
                "请检查网络、GitHub 服务状态和代理配置，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("category=llm_auth_failed")) {
            return new FailureSummary(
                "llm_auth_failed",
                "LLM 鉴权失败",
                "请检查 LLM API Key、Provider 和 Base URL 配置，保存成功后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_rate_limited")) {
            return new FailureSummary(
                "llm_rate_limited",
                "LLM 调用受限",
                "请稍后重试，或调整供应商额度、并发与限流配置。"
            );
        }
        if (lowerDetail.contains("category=llm_service_unavailable")) {
            return new FailureSummary(
                "llm_service_unavailable",
                "LLM 服务暂时不可用",
                "请稍后重试，必要时切换模型或启用规则兜底。"
            );
        }
        if (lowerDetail.contains("category=llm_timeout")) {
            return new FailureSummary(
                "llm_timeout",
                "LLM 响应超时",
                "请检查模型服务状态、网络和超时配置，稍后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_request_invalid")
            || lowerDetail.contains("category=llm_model_or_endpoint_not_found")) {
            return new FailureSummary(
                "llm_request_invalid",
                "LLM 请求配置无效",
                "请检查模型名称、Base URL、请求参数和供应商兼容性配置。"
            );
        }
        if (lowerDetail.contains("401") || lowerDetail.contains("bad credentials")
            || lowerDetail.contains("unauthorized") || lowerDetail.contains("requires authentication")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。"
            );
        }
        if (lowerDetail.contains("403") || lowerDetail.contains("forbidden")
            || lowerDetail.contains("resource not accessible") || lowerDetail.contains("permission")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。"
            );
        }
        if (lowerDetail.contains("404") || lowerDetail.contains("not found")) {
            return new FailureSummary(
                "github_resource_not_found",
                "PR 或仓库不存在/不可访问",
                "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。"
            );
        }
        if (lowerDetail.contains("rate limit")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerDetail.contains("timeout") || lowerDetail.contains("timed out")) {
            return new FailureSummary(
                "external_service_timeout",
                "外部服务响应超时",
                "请检查网络、GitHub 和 LLM 服务状态，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("unable to parse llm review result") || lowerDetail.contains("llm review result")) {
            return new FailureSummary(
                "llm_result_parse_failed",
                "LLM 输出解析失败",
                "请检查 LLM 模型返回格式或临时启用规则兜底后重试。"
            );
        }
        if (lowerDetail.contains("llm config is incomplete") || lowerDetail.contains("api key")) {
            return new FailureSummary(
                "llm_config_incomplete",
                "LLM 配置不完整",
                "请在系统配置中补全 LLM Provider、模型和密钥，保存后再重试。"
            );
        }
        return new FailureSummary(
            "review_execution_failed",
            "审查执行失败",
            "请检查 GitHub/LLM 集成配置和任务时间线，修复后点击重试。"
        );
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

    private ChangedFileDto toChangedFileDto(ChangedFile file) {
        return new ChangedFileDto(file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions());
    }

    private ReviewFindingDto toFindingDto(ReviewFinding finding) {
        return new ReviewFindingDto(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            lower(resolveFindingFeedbackStatus(finding)),
            finding.getFeedbackNote(),
            finding.getFeedbackBy(),
            formatDateTimeOrNull(finding.getFeedbackAt())
        );
    }

    private MissingTestDto toMissingTestDto(ReviewFinding finding) {
        return new MissingTestDto(
            finding.getFilePath(),
            finding.getMethodName(),
            finding.getTestType(),
            finding.getRecommendation()
        );
    }

    private GithubCommentPreviewItem toGithubCommentPreviewItem(
        ReviewFinding finding,
        ChangedFile changedFile,
        GithubCommentPublication publication
    ) {
        String targetType = resolveCommentTargetType(finding, changedFile);
        String reason = resolveCommentReason(targetType, finding, changedFile);
        boolean published = isPublished(publication);
        boolean actionable = isActionableFinding(finding);
        return new GithubCommentPreviewItem(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            buildGithubCommentBody(finding),
            !published && actionable,
            targetType,
            published ? "GitHub comment already published" : actionable ? reason : feedbackSkipReason(finding),
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER),
            lower(resolveFindingFeedbackStatus(finding))
        );
    }

    private GithubCommentPreviewItem toPrSummaryCommentPreviewItem(
        PrReviewSummaryDto summary,
        GithubCommentPublication publication
    ) {
        boolean published = isPublished(publication);
        return new GithubCommentPreviewItem(
            null,
            summary.overallRisk(),
            "PR 总评",
            null,
            summary.summary(),
            summary.mergeRecommendation(),
            summary.githubCommentBody(),
            !published,
            "pull_request",
            published ? "GitHub comment already published" : null,
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER),
            "valid"
        );
    }

    private boolean isPublished(GithubCommentPublication publication) {
        // 降级为 PR 总评评论也已经产生 GitHub URL，因此也应参与幂等跳过。
        return publication != null
            && Boolean.TRUE.equals(publication.getSuccess())
            && StringUtils.hasText(publication.getGithubUrl());
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

    private String resolveCommentTargetType(ReviewFinding finding, ChangedFile changedFile) {
        if (
            StringUtils.hasText(finding.getFilePath())
                && finding.getLineNumber() != null
                && finding.getLineNumber() > 0
                && changedFile != null
                && !isDeletedChange(changedFile.getChangeType())
        ) {
            return "line";
        }
        return "pull_request";
    }

    private String resolveCommentReason(String targetType, ReviewFinding finding, ChangedFile changedFile) {
        if ("line".equals(targetType)) {
            return null;
        }
        if (!StringUtils.hasText(finding.getFilePath())) {
            return "Finding is missing file path and will be posted as a PR comment";
        }
        if (finding.getLineNumber() == null || finding.getLineNumber() <= 0) {
            return "Finding is missing a valid line number and will be posted as a PR comment";
        }
        if (changedFile == null) {
            return "Finding file is not in the changed files list and will be posted as a PR comment";
        }
        if (isDeletedChange(changedFile.getChangeType())) {
            return "Deleted files will be posted as PR comments";
        }
        return "Finding will be posted as a PR comment";
    }

    private boolean isDeletedChange(String changeType) {
        if (!StringUtils.hasText(changeType)) {
            return false;
        }
        return Set.of("D", "DELETE", "DELETED", "REMOVE", "REMOVED").contains(changeType.trim().toUpperCase());
    }

    private String buildGithubCommentBody(ReviewFinding finding) {
        StringBuilder body = new StringBuilder();
        body.append("**RepoGuard ");
        if (StringUtils.hasText(finding.getSeverity())) {
            body.append(finding.getSeverity().trim().toUpperCase());
        } else {
            body.append("INFO");
        }
        body.append(" finding**");

        if (StringUtils.hasText(finding.getRuleId())) {
            body.append(" · `").append(finding.getRuleId().trim()).append("`");
        }

        if (StringUtils.hasText(finding.getMessage())) {
            body.append("\n\n").append(finding.getMessage().trim());
        }

        if (StringUtils.hasText(finding.getRecommendation())) {
            body.append("\n\n**建议**：").append(finding.getRecommendation().trim());
        }

        return body.toString();
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

    private String resolveStoredSource(String source) {
        return StringUtils.hasText(source) ? source : SOURCE_MANUAL_INPUT;
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

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private boolean isActionableFinding(ReviewFinding finding) {
        String feedbackStatus = resolveFindingFeedbackStatus(finding);
        return FEEDBACK_UNREVIEWED.equals(feedbackStatus) || FEEDBACK_VALID.equals(feedbackStatus);
    }

    private String feedbackSkipReason(ReviewFinding finding) {
        return switch (resolveFindingFeedbackStatus(finding)) {
            case FEEDBACK_FALSE_POSITIVE -> "Finding marked as false positive and will not be published";
            case FEEDBACK_FIXED -> "Finding marked as fixed and will not be published";
            case FEEDBACK_IGNORED -> "Finding marked as ignored and will not be published";
            default -> "Finding is not actionable and will not be published";
        };
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

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean equalsIgnoreCase(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private String formatDuration(Integer durationSeconds) {
        int totalSeconds = durationSeconds == null ? 0 : durationSeconds;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " 分 " + seconds + " 秒";
    }

    private record FailureSummary(String category, String reason, String suggestion) {
    }
}
