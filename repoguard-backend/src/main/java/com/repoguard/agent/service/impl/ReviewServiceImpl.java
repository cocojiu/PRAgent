package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
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
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
                integrationConfigMapper,
                githubPullRequestClient,
                metrics,
                notificationDispatchService
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
        return githubCommentApplicationService.publishGithubComments(id);
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
}
