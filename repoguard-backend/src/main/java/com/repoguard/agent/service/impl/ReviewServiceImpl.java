package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ChangedFileDto;
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
import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
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
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.service.ReviewService;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReviewServiceImpl implements ReviewService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String GITHUB_PROVIDER = "GITHUB";

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
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        Page<ReviewTask> page = reviewTaskMapper.selectPage(
            Page.of(query.page(), query.pageSize()),
            buildListWrapper(query)
        );
        return new PageResponse<>(
            page.getRecords().stream().map(this::toListItem).toList(),
            page.getTotal()
        );
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

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
        savePublicationBatch(response);
        return response;
    }

    @Override
    public ReviewTaskDetail getReviewDetail(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        List<ChangedFileDto> changedFiles = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, id)
                .orderByAsc(ChangedFile::getId)
        ).stream().map(this::toChangedFileDto).toList();

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, id)
                .orderByAsc(ReviewFinding::getId)
        );

        List<ReviewFindingDto> findingDtos = findings.stream()
            .filter(finding -> "FINDING".equals(finding.getCategory()))
            .map(this::toFindingDto)
            .toList();

        List<MissingTestDto> missingTests = findings.stream()
            .filter(finding -> "MISSING_TEST".equals(finding.getCategory()))
            .map(this::toMissingTestDto)
            .toList();

        List<ReviewTimelineItem> timeline = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, id)
                .orderByAsc(ReviewTimeline::getSortOrder)
        ).stream().map(this::toTimelineItem).toList();

        return toDetail(task, findingDtos, missingTests, changedFiles, timeline);
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
                .eq(ReviewFinding::getCategory, "FINDING")
                .orderByAsc(ReviewFinding::getId)
        );
        Map<Long, GithubCommentPublication> publicationByFindingId = loadPublicationByFindingId(id, findings);

        List<GithubCommentPreviewItem> items = findings.stream()
            .map(finding -> toGithubCommentPreviewItem(
                finding,
                changedFileByPath.get(finding.getFilePath()),
                publicationByFindingId.get(finding.getId())
            ))
            .toList();

        int commentableCount = (int) items.stream().filter(GithubCommentPreviewItem::commentable).count();
        int publishedCount = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.published())).count();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            buildGithubCommentWritebackCheck(task),
            items.size(),
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
        boolean connectionHealthy = config != null
            && "CONFIGURED".equals(config.getStatus())
            && !StringUtils.hasText(config.getLastError());

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
        if (!connectionHealthy) {
            return "connection_failed";
        }
        if (!repositoryConfigured) {
            return "repository_not_configured";
        }
        if (!repositoryMatched) {
            return "repository_mismatch";
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

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        // 批次倒序展示，用户先看到最近一次回写结果。
        List<GithubCommentPublicationBatch> batches = githubCommentPublicationBatchMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublicationBatch>()
                .eq(GithubCommentPublicationBatch::getTaskId, id)
                .orderByDesc(GithubCommentPublicationBatch::getCreatedAt)
                .orderByDesc(GithubCommentPublicationBatch::getId)
        );
        if (batches == null || batches.isEmpty()) {
            return new GithubCommentPublicationHistoryResponse(task.getId(), List.of());
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
        return new GithubCommentPublicationHistoryResponse(task.getId(), batchDtos);
    }

    @Override
    @Transactional
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        String organization = request.organization().trim();
        String repository = request.repository().trim();
        String commit = resolveCommit(request);
        ReviewTask existingTask = findExistingManualTask(organization, repository, request.prNumber(), commit);
        if (existingTask != null) {
            return new ManualReviewResponse(existingTask.getId(), lower(existingTask.getStatus()), "Review task already exists", true);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        ReviewTask task = new ReviewTask();
        task.setPrNumber(request.prNumber());
        task.setTitle(resolveTitle(request));
        task.setRepository(repository);
        task.setOrganization(organization);
        task.setCommitSha(commit);
        task.setBranchName(resolveBranch(request));
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setMqRetries(0);
        task.setLlmStatus("PENDING");
        task.setPrUrl(buildPrUrl(request));
        task.setCreatedAt(createdAt);
        task.setDurationSeconds(0);

        reviewTaskMapper.insert(task);
        insertInitialTimeline(task.getId(), createdAt);
        reviewTaskPublisher.publish(new ReviewTaskMessage(
            task.getId(),
            organization,
            repository,
            request.prNumber(),
            commit,
            createdAt
        ));
        return new ManualReviewResponse(task.getId(), "queued", "Review task queued", false);
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
                    item.author(),
                    item.url(),
                    item.updatedAt()
                ))
                .toList()
        );
    }

    private LambdaQueryWrapper<ReviewTask> buildListWrapper(ReviewQuery query) {
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<ReviewTask>()
            .orderByDesc(ReviewTask::getCreatedAt);

        // 前端使用小写筛选值，数据库保存类枚举的大写值，这里统一做一次转换。
        if (StringUtils.hasText(query.repository())) {
            wrapper.eq(ReviewTask::getRepository, query.repository().trim());
        }
        if (StringUtils.hasText(query.status())) {
            wrapper.eq(ReviewTask::getStatus, query.status().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.riskLevel())) {
            wrapper.eq(ReviewTask::getRiskLevel, query.riskLevel().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim();
            Integer prNumber = parseIntegerOrNull(keyword);
            // 关键字同时匹配可读字段；当关键字是数字时，也匹配 PR 编号。
            wrapper.and(nested -> nested
                .like(ReviewTask::getTitle, keyword)
                .or()
                .like(ReviewTask::getRepository, keyword)
                .or()
                .like(ReviewTask::getOrganization, keyword)
                .or()
                .like(ReviewTask::getCommitSha, keyword)
                .or(prNumber != null)
                .eq(prNumber != null, ReviewTask::getPrNumber, prNumber)
            );
        }
        return wrapper;
    }

    private Integer parseIntegerOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ReviewTaskDetail toDetail(
        ReviewTask task,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        List<ReviewTimelineItem> timeline
    ) {
        ReviewTaskListItem item = toListItem(task);
        return new ReviewTaskDetail(
            item.id(),
            item.prNumber(),
            item.title(),
            item.repository(),
            item.organization(),
            item.commit(),
            item.branch(),
            item.status(),
            item.riskLevel(),
            item.mqRetries(),
            item.llmStatus(),
            item.createdAt(),
            item.duration(),
            task.getPrUrl(),
            findings,
            missingTests,
            changedFiles,
            timeline,
            new LlmStatusDto(item.llmStatus(), item.duration(), item.riskLevel()),
            new RabbitMqStatusDto(task.getMqRetries() + 1, task.getMqRetries(), "confirmed")
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
        return new GithubCommentPublishItem(
            result.findingId(),
            result.path(),
            result.line(),
            result.targetType(),
            result.success(),
            result.status(),
            result.message(),
            result.url(),
            result.commentId(),
            publication.getPublishedAt() == null ? null : publication.getPublishedAt().format(DATE_TIME_FORMATTER)
        );
    }

    private void savePublicationBatch(GithubCommentPublishResponse response) {
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
        return new GithubCommentPublicationHistoryItem(
            item.getFindingId(),
            item.getFilePath(),
            item.getLineNumber(),
            item.getTargetType(),
            item.getSuccess(),
            item.getStatus(),
            item.getMessage(),
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

    private GithubCommentPublication savePublication(Long taskId, GithubReviewCommentResult result) {
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublication publication = githubCommentPublicationMapper.selectOne(
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

    private ReviewTaskListItem toListItem(ReviewTask task) {
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
            task.getCreatedAt().format(DATE_TIME_FORMATTER),
            formatDuration(task.getDurationSeconds())
        );
    }

    private ChangedFileDto toChangedFileDto(ChangedFile file) {
        return new ChangedFileDto(file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions());
    }

    private ReviewFindingDto toFindingDto(ReviewFinding finding) {
        return new ReviewFindingDto(
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation()
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
        return new GithubCommentPreviewItem(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            buildGithubCommentBody(finding),
            !published,
            targetType,
            published ? "GitHub comment already published" : reason,
            published,
            publication == null ? null : publication.getStatus(),
            publication == null ? null : publication.getGithubUrl(),
            publication == null ? null : publication.getMessage(),
            publication == null || publication.getPublishedAt() == null
                ? null
                : publication.getPublishedAt().format(DATE_TIME_FORMATTER)
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
                default -> "pending";
            }
        );
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

    private ReviewTask findExistingManualTask(String organization, String repository, Integer prNumber, String commit) {
        if (!StringUtils.hasText(commit) || "pending".equals(commit)) {
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

    private String buildPrUrl(ManualReviewRequest request) {
        return "https://github.com/"
            + request.organization().trim()
            + "/"
            + request.repository().trim()
            + "/pull/"
            + request.prNumber();
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
}
