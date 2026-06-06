package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
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
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.service.ReviewService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final GithubPullRequestClient githubPullRequestClient;

    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
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
                false,
                "skipped",
                item.reason(),
                null
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
        return new GithubCommentPublishResponse(
            task.getId(),
            preview.totalFindings(),
            drafts.size(),
            succeededCount,
            failedCount,
            skippedItems.size(),
            items
        );
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

        List<GithubCommentPreviewItem> items = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, id)
                .eq(ReviewFinding::getCategory, "FINDING")
                .orderByAsc(ReviewFinding::getId)
        ).stream()
            .map(finding -> toGithubCommentPreviewItem(finding, changedFileByPath.get(finding.getFilePath())))
            .toList();

        int commentableCount = (int) items.stream().filter(GithubCommentPreviewItem::commentable).count();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            items.size(),
            commentableCount,
            items.size() - commentableCount,
            items
        );
    }

    @Override
    @Transactional
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        String organization = request.organization().trim();
        String repository = request.repository().trim();
        String commit = resolveCommit(request);
        ReviewTask existingTask = findExistingManualTask(organization, repository, request.prNumber(), commit);
        if (existingTask != null) {
            return new ManualReviewResponse(existingTask.getId(), lower(existingTask.getStatus()), "Review task already exists");
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
        return new ManualReviewResponse(task.getId(), "queued", "Review task queued");
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
                .map(this::toGithubCommentPublishItem)
                .toList();
        } catch (RuntimeException ex) {
            String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            return drafts.stream()
                .map(draft -> new GithubCommentPublishItem(
                    draft.findingId(),
                    draft.path(),
                    draft.line(),
                    false,
                    "failed",
                    message,
                    null
                ))
                .toList();
        }
    }

    private GithubCommentPublishItem toGithubCommentPublishItem(GithubReviewCommentResult result) {
        return new GithubCommentPublishItem(
            result.findingId(),
            result.path(),
            result.line(),
            result.success(),
            result.status(),
            result.message(),
            result.url()
        );
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

    private GithubCommentPreviewItem toGithubCommentPreviewItem(ReviewFinding finding, ChangedFile changedFile) {
        String targetType = resolveCommentTargetType(finding, changedFile);
        String reason = resolveCommentReason(targetType, finding, changedFile);
        return new GithubCommentPreviewItem(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            buildGithubCommentBody(finding),
            true,
            targetType,
            reason
        );
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

    private String formatDuration(Integer durationSeconds) {
        int totalSeconds = durationSeconds == null ? 0 : durationSeconds;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " 分 " + seconds + " 秒";
    }
}
