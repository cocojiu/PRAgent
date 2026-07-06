package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.service.GithubCommentApplicationService;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import com.repoguard.agent.service.GithubPullRequestOptionService;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewTaskQueryService;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReviewServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPublicationMapper githubCommentPublicationMapper = org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(GithubPullRequestClient.class);
    private final CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
    private final ReviewTaskStateMachine reviewTaskStateMachine = new ReviewTaskStateMachine();
    private final ReviewTimelineQueryService reviewTimelineQueryService =
        new ReviewTimelineQueryService(reviewTimelineMapper);
    private final ReviewTaskListItemAssembler reviewTaskListItemAssembler = new ReviewTaskListItemAssembler();
    private final ReviewTaskQueryService reviewTaskQueryService = new ReviewTaskQueryServiceImpl(
        reviewTaskMapper,
        new ReviewTaskDetailAssembler(
            new ReviewRiskProfileBuilder(),
            new PrReviewSummaryBuilder()
        ),
        new ReviewTaskDetailDataLoader(
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineQueryService,
            new ReviewTaskDetailFindingAssembler()
        ),
        new ReviewTaskQueryItemLoader(
            reviewTaskMapper,
            new ReviewFailureSummaryResolver(),
            reviewTimelineQueryService,
            reviewTaskListItemAssembler
        ),
        new ReviewTaskStatusAssembler(),
        new ReviewTaskListQueryBuilder()
    );
    private final ReviewTaskCommandService reviewTaskCommandService = new ReviewTaskCommandServiceImpl(
        humanReviewCommandService(),
        reviewTaskRetryService(reviewTaskAfterCommitPublisher()),
        manualReviewCreationService(
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            reviewTaskAfterCommitPublisher()
        )
    );
    private final FindingFeedbackService findingFeedbackService = new FindingFeedbackServiceImpl(
        reviewTaskMapper,
        reviewFindingMapper,
        timelineAppender(),
        cacheEvictionService
    );
    private final GithubWritebackFailureClassifier githubWritebackFailureClassifier =
        new GithubWritebackFailureClassifier();
    private final GithubCommentPublicationRecorder githubCommentPublicationRecorder =
        new GithubCommentPublicationRecorder(
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper
        );
    private final GithubCommentPreviewService githubCommentPreviewService = new GithubCommentPreviewServiceImpl(
        reviewTaskMapper,
        githubIntegrationProvider,
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder(),
        new ReviewTaskListItemAssembler(),
        new GithubCommentPreviewDataLoader(changedFileMapper, reviewFindingMapper),
        new GithubCommentPreviewPublicationLoader(githubCommentPublicationMapper),
        new GithubCommentPreviewResponseAssembler(
            new GithubCommentWritebackCheckBuilder(),
            new GithubCommentPreviewItemBuilder()
        )
    );
    private final GithubCommentPublishService githubCommentPublishService = new GithubCommentPublishServiceImpl(
        reviewTaskMapper,
        null,
        null,
        githubCommentPreviewService,
        new GithubCommentPublishGuard(reviewTaskStateMachine),
        new GithubCommentPublishPlanBuilder(),
        new GithubCommentDraftPublisher(
            githubPullRequestClient,
            githubCommentPublicationRecorder,
            githubWritebackFailureClassifier
        ),
        githubCommentPublicationRecorder
    );
    private final GithubCommentHistoryQueryService githubCommentHistoryQueryService =
        new GithubCommentHistoryQueryServiceImpl(
            reviewTaskMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            new GithubCommentPublicationHistoryAssembler(githubWritebackFailureClassifier)
        );
    private final GithubCommentApplicationService githubCommentApplicationService = new GithubCommentApplicationServiceImpl(
        githubCommentPreviewService,
        githubCommentPublishService,
        githubCommentHistoryQueryService
    );
    private final GithubPullRequestOptionService githubPullRequestOptionService = new GithubPullRequestOptionServiceImpl(
        githubPullRequestClient,
        reviewTaskMapper
    );
    private final ReviewServiceImpl service = new ReviewServiceImpl(
        reviewTaskQueryService,
        reviewTaskCommandService,
        findingFeedbackService,
        githubCommentApplicationService,
        githubPullRequestOptionService
    );

    private HumanReviewCommandService humanReviewCommandService() {
        return new HumanReviewCommandService(
            reviewTaskMapper,
            timelineAppender(),
            reviewTaskStateMachine,
            cacheEvictionService
        );
    }

    private ReviewTaskRetryService reviewTaskRetryService(ReviewTaskAfterCommitPublisher afterCommitPublisher) {
        return new ReviewTaskRetryService(
            reviewTaskMapper,
            timelineAppender(),
            reviewTaskStateMachine,
            afterCommitPublisher,
            cacheEvictionService
        );
    }

    private ManualReviewCreationService manualReviewCreationService(
        ManualReviewIdempotencyCoordinator coordinator,
        ReviewTaskAfterCommitPublisher afterCommitPublisher
    ) {
        return new ManualReviewCreationService(
            reviewTaskMapper,
            timelineAppender(),
            null,
            cacheEvictionService,
            reviewTaskStateMachine,
            (TransactionTemplate) null,
            coordinator,
            afterCommitPublisher
        );
    }

    private ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher() {
        return new ReviewTaskAfterCommitPublisher(
            reviewTaskPublisher,
            new ReviewTaskPublishOutboxStore(
                reviewTaskMapper,
                timelineAppender(),
                reviewTaskStateMachine
            ),
            Runnable::run
        );
    }

    private ReviewTimelineAppender timelineAppender() {
        return new ReviewTimelineAppender(reviewTimelineMapper);
    }

    @Test
    void getGithubCommentPreviewBuildsCommentDraftsAndBlocksMissingLine() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubIntegrationProvider.getSettings())
            .thenReturn(githubSettings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "命令与描述未正确分隔", "添加空格或换行"),
            finding(2L, "LOW", "README", 3, "文档可读性不足", "补充分隔符"),
            finding(3L, "LOW", "README", 4, "可能导致误解", "调整格式"),
            finding(4L, "LOW", "README", null, "文件末尾缺少换行符", "添加换行符")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.totalFindings()).isEqualTo(4);
        assertThat(preview.commentableCount()).isEqualTo(5);
        assertThat(preview.blockedCount()).isZero();
        assertThat(preview.writebackCheck().status()).isEqualTo("ready");
        assertThat(preview.writebackCheck().repositoryMatched()).isTrue();
        assertThat(preview.items().getFirst().findingId()).isNull();
        assertThat(preview.items().getFirst().commentBody()).contains("## RepoGuard PR 总评");
        assertThat(preview.items().get(1).commentBody())
            .contains("**RepoGuard LOW finding**")
            .contains("命令与描述未正确分隔")
            .contains("**建议**：添加空格或换行");
        assertThat(preview.items().getLast().commentable()).isTrue();
        assertThat(preview.items().getLast().targetType()).isEqualTo("pull_request");
        assertThat(preview.items().getLast().reason()).isEqualTo("Finding is missing a valid line number and will be posted as a PR comment");
    }

    @Test
    void getGithubCommentPreviewWarnsWhenConfiguredRepositoryDiffersFromTaskRepository() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubIntegrationProvider.getSettings())
            .thenReturn(githubSettings("other-owner", "other-repo", "CONFIGURED", "ghp_test", null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("repository_mismatch");
        assertThat(preview.writebackCheck().level()).isEqualTo("warning");
        assertThat(preview.writebackCheck().repositoryMatched()).isFalse();
        assertThat(preview.writebackCheck().messages())
            .contains("当前任务仓库与 GitHub 集成默认仓库不一致，请确认 Token 对目标仓库有评论权限。");
    }

    @Test
    void getGithubCommentPreviewWarnsWhenGithubTokenIsMissing() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubIntegrationProvider.getSettings())
            .thenReturn(githubSettings("octocat", "Hello-World", "NOT_CONFIGURED", null, null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("token_missing");
        assertThat(preview.writebackCheck().level()).isEqualTo("danger");
        assertThat(preview.writebackCheck().tokenConfigured()).isFalse();
        assertThat(preview.writebackCheck().messages())
            .contains("GitHub Token 未配置，请先到集成配置页保存 Token。");
    }

    @Test
    void getGithubCommentPreviewKeepsReadyWhenOnlyStaleGithubConnectionErrorExists() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(
            "octocat",
            "Hello-World",
            "FAILED",
            "ghp_test",
            "404 Not Found"
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("ready");
        assertThat(preview.writebackCheck().level()).isEqualTo("success");
        assertThat(preview.writebackCheck().connectionHealthy()).isTrue();
        assertThat(preview.writebackCheck().lastError()).contains("404 Not Found");
    }

    @Test
    void publishGithubCommentsSendsOnlyCommentableDrafts() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(null);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "命令与描述未正确分隔", "添加空格或换行"),
            finding(2L, "LOW", "README", null, "文件末尾缺少换行符", "添加换行符")
        ));
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(null, "PR 总评", null, "pull_request", true, "published", "GitHub comment published", "https://github.com/comment/summary", 100L),
            new GithubReviewCommentResult(1L, "README", 2, "line", true, "published", "GitHub comment published", "https://github.com/comment/1", 101L),
            new GithubReviewCommentResult(2L, "README", null, "pull_request", true, "published", "GitHub comment published", "https://github.com/comment/2", 102L)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.attemptedCount()).isEqualTo(3);
        assertThat(result.succeededCount()).isEqualTo(3);
        assertThat(result.failedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.items()).extracting("status").containsExactly("published", "published", "published");
        verify(githubCommentPublicationMapper, org.mockito.Mockito.times(3)).insert(any(GithubCommentPublication.class));
        verify(githubCommentPublicationBatchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(githubCommentPublicationBatchItemMapper, org.mockito.Mockito.times(3)).insert(any(GithubCommentPublicationBatchItem.class));
    }

    @Test
    void publishGithubCommentsAddsReadableFailureForPermissionError() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(null);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(
                1L,
                "README",
                2,
                "line",
                false,
                "failed",
                "403 Resource not accessible by integration",
                null,
                null
            )
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.items().getFirst().failureReason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.items().getFirst().failureSuggestion()).contains("评论权限");
    }

    @Test
    void publishGithubCommentsSkipsAlreadyPublishedFindings() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setTaskId(521L);
        publication.setFindingId(1L);
        publication.setTargetType("line");
        publication.setSuccess(true);
        publication.setStatus("published");
        publication.setGithubUrl("https://github.com/comment/1");
        publication.setMessage("GitHub comment published");
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 7, 10, 0));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of(publication));
        GithubCommentPublication summaryPublication = new GithubCommentPublication();
        summaryPublication.setTaskId(521L);
        summaryPublication.setFindingId(null);
        summaryPublication.setTargetType("pull_request");
        summaryPublication.setSuccess(true);
        summaryPublication.setStatus("published");
        summaryPublication.setGithubUrl("https://github.com/comment/summary");
        summaryPublication.setMessage("GitHub comment published");
        summaryPublication.setPublishedAt(LocalDateTime.of(2026, 6, 7, 10, 1));
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(summaryPublication);

        var result = service.publishGithubComments(521L);

        assertThat(result.attemptedCount()).isZero();
        assertThat(result.succeededCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.items()).extracting("status").containsExactly("already_published", "already_published");
        assertThat(result.items()).extracting("url").contains("https://github.com/comment/summary", "https://github.com/comment/1");
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
        verify(githubCommentPublicationBatchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(githubCommentPublicationBatchItemMapper, org.mockito.Mockito.times(2)).insert(any(GithubCommentPublicationBatchItem.class));
    }

    @Test
    void publishGithubCommentsRejectsPendingHumanReviewTask() {
        ReviewTask task = task();
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        task.setStatus("PENDING_HUMAN_REVIEW");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);

        assertThatThrownBy(() -> service.publishGithubComments(521L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Human review");

        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
    }

    @Test
    void submitHumanReviewChangesRequestedUpdatesTaskAndTimeline() {
        ReviewTask task = task();
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        task.setStatus("PENDING_HUMAN_REVIEW");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);

        var response = service.submitHumanReview(
            521L,
            new HumanReviewRequest("changes_requested", "修复高风险问题后重新审查"),
            "review-lead"
        );

        assertThat(response.status()).isEqualTo("changes_requested");
        assertThat(response.humanReviewStatus()).isEqualTo("changes_requested");
        assertThat(response.humanReviewBy()).isEqualTo("review-lead");
        assertThat(task.getStatus()).isEqualTo("CHANGES_REQUESTED");
        assertThat(task.getHumanReviewStatus()).isEqualTo("CHANGES_REQUESTED");
        assertThat(task.getHumanReviewNote()).isEqualTo("修复高风险问题后重新审查");
        assertThat(task.getHumanReviewBy()).isEqualTo("review-lead");
        verify(reviewTaskMapper).updateById(task);
        verify(reviewTimelineMapper).insert(any(ReviewTimeline.class));
    }

    @Test
    void updateFindingFeedbackStoresDecisionAndTimeline() {
        ReviewTask task = task();
        ReviewFinding finding = finding(9L, "MEDIUM", "src/App.java", 42, "Use logger", "Replace stdout");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(reviewFindingMapper.selectById(9L)).thenReturn(finding);

        var response = service.updateFindingFeedback(
            521L,
            9L,
            new FindingFeedbackRequest("false_positive", "Covered by framework"),
            "review-lead"
        );

        assertThat(response.findingId()).isEqualTo(9L);
        assertThat(response.taskId()).isEqualTo(521L);
        assertThat(response.feedbackStatus()).isEqualTo("false_positive");
        assertThat(response.feedbackNote()).isEqualTo("Covered by framework");
        assertThat(response.feedbackBy()).isEqualTo("review-lead");
        assertThat(finding.getFeedbackStatus()).isEqualTo("FALSE_POSITIVE");
        assertThat(finding.getFeedbackBy()).isEqualTo("review-lead");
        assertThat(finding.getFeedbackAt()).isNotNull();
        verify(reviewFindingMapper).updateById(finding);
        verify(reviewTimelineMapper).insert(any(ReviewTimeline.class));
    }

    @Test
    void getGithubCommentPreviewSkipsNonActionableFeedback() {
        ReviewFinding validFinding = finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger");
        validFinding.setFeedbackStatus("VALID");
        ReviewFinding falsePositive = finding(2L, "LOW", "README", 3, "Known safe", "No change needed");
        falsePositive.setFeedbackStatus("FALSE_POSITIVE");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubIntegrationProvider.getSettings())
            .thenReturn(githubSettings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(validFinding, falsePositive));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.totalFindings()).isEqualTo(2);
        assertThat(preview.commentableCount()).isEqualTo(2);
        assertThat(preview.blockedCount()).isEqualTo(1);
        assertThat(preview.items().getFirst().findingId()).isNull();
        assertThat(preview.items().get(1).feedbackStatus()).isEqualTo("valid");
        assertThat(preview.items().getLast().commentable()).isFalse();
        assertThat(preview.items().getLast().feedbackStatus()).isEqualTo("false_positive");
        assertThat(preview.items().getLast().reason()).isEqualTo("Finding marked as false positive and will not be published");
    }

    @Test
    void publishGithubCommentsSkipsNonActionableFeedback() {
        ReviewFinding validFinding = finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger");
        validFinding.setFeedbackStatus("VALID");
        ReviewFinding ignoredFinding = finding(2L, "LOW", "README", 3, "Known issue", "Track separately");
        ignoredFinding.setFeedbackStatus("IGNORED");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(null);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(validFinding, ignoredFinding));
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(null, "PR 总评", null, "pull_request", true, "published", "GitHub comment published", "https://github.com/comment/summary", 100L),
            new GithubReviewCommentResult(1L, "README", 2, "line", true, "published", "GitHub comment published", "https://github.com/comment/1", 101L)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.succeededCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("findingId").containsExactly(null, 1L, 2L);
        assertThat(result.items().getLast().status()).isEqualTo("skipped");
        assertThat(result.items().getLast().message()).isEqualTo("Finding marked as ignored and will not be published");
    }

    @Test
    void getGithubCommentPublicationHistoryReturnsBatchesAndItems() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(10L);
        batch.setTaskId(521L);
        batch.setStatus("completed");
        batch.setTotalFindings(1);
        batch.setAttemptedCount(1);
        batch.setSucceededCount(1);
        batch.setFailedCount(0);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setBatchId(10L);
        item.setTaskId(521L);
        item.setFindingId(1L);
        item.setFilePath("README");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(true);
        item.setStatus("published");
        item.setMessage("GitHub comment published");
        item.setGithubUrl("https://github.com/comment/1");
        item.setGithubCommentId(101L);
        item.setPublishedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        Page<GithubCommentPublicationBatch> batchPage = Page.of(1, 20);
        batchPage.setRecords(List.of(batch));
        batchPage.setTotal(1);
        when(githubCommentPublicationBatchMapper.selectPage(any(), any())).thenReturn(batchPage);
        when(githubCommentPublicationBatchItemMapper.selectList(any())).thenReturn(List.of(item));

        var history = service.getGithubCommentPublicationHistory(521L, 1, 20, "completed");

        assertThat(history.taskId()).isEqualTo(521L);
        assertThat(history.total()).isEqualTo(1);
        assertThat(history.page()).isEqualTo(1);
        assertThat(history.pageSize()).isEqualTo(20);
        assertThat(history.status()).isEqualTo("completed");
        assertThat(history.batches()).hasSize(1);
        assertThat(history.batches().getFirst().batchId()).isEqualTo(10L);
        assertThat(history.batches().getFirst().items()).hasSize(1);
        assertThat(history.batches().getFirst().items().getFirst().status()).isEqualTo("published");
        assertThat(history.batches().getFirst().items().getFirst().url()).isEqualTo("https://github.com/comment/1");
    }

    @Test
    void getGithubCommentPublicationHistoryAddsReadableFailureForTokenError() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(11L);
        batch.setTaskId(521L);
        batch.setStatus("failed");
        batch.setTotalFindings(1);
        batch.setAttemptedCount(1);
        batch.setSucceededCount(0);
        batch.setFailedCount(1);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setBatchId(11L);
        item.setTaskId(521L);
        item.setFindingId(1L);
        item.setFilePath("README");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(false);
        item.setStatus("failed");
        item.setMessage("401 Bad credentials");
        Page<GithubCommentPublicationBatch> batchPage = Page.of(1, 20);
        batchPage.setRecords(List.of(batch));
        batchPage.setTotal(1);
        when(githubCommentPublicationBatchMapper.selectPage(any(), any())).thenReturn(batchPage);
        when(githubCommentPublicationBatchItemMapper.selectList(any())).thenReturn(List.of(item));

        var history = service.getGithubCommentPublicationHistory(521L);

        var historyItem = history.batches().getFirst().items().getFirst();
        assertThat(historyItem.failureCategory()).isEqualTo("github_token_invalid");
        assertThat(historyItem.failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(historyItem.failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void listReviewsFiltersBySourceAndTriggerSource() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(task()));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listReviews(new ReviewQuery(
            1,
            20,
            null,
            null,
            null,
            "github_pr_picker",
            "existing_reused",
            null
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviewTaskMapper).selectPage(any(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("source", "trigger_source");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("GITHUB_PR_PICKER", "EXISTING_REUSED");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().source()).isEqualTo("github_pr_picker");
    }

    @Test
    void listReviewsAddsReadableFailureSummaryFromTimeline() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(failedTask));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline("Review failed: 401 Bad credentials")));

        var result = service.listReviews(new ReviewQuery(1, 20, null, null, null, null, null, null));

        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_token_invalid");
        assertThat(result.items().getFirst().failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(result.items().getFirst().failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void listReviewsAddsReadableFailureSummaryFromExternalCallCategory() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(failedTask));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline(
            "Review failed: GitHub external call failed: category=github_rate_limited retryable=true status=429"
        )));

        var result = service.listReviews(new ReviewQuery(1, 20, null, null, null, null, null, null));

        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_rate_limited");
        assertThat(result.items().getFirst().failureReason()).contains("GitHub API");
    }

    @Test
    void getReviewDetailAddsReadableFailureSummaryFromTimeline() {
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        when(reviewTaskMapper.selectById(521L)).thenReturn(failedTask);
        mockDetailPages(List.of(), List.of(), List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(
            timeline("Task queued"),
            timeline("Review failed: 403 Resource not accessible by integration")
        ));

        var result = service.getReviewDetail(521L);

        assertThat(result.failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.failureReason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.failureSuggestion()).contains("目标仓库和 PR");
    }

    @Test
    void getReviewDetailAddsReadableLlmFailureSummaryFromExternalCallCategory() {
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        when(reviewTaskMapper.selectById(521L)).thenReturn(failedTask);
        mockDetailPages(List.of(), List.of(), List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(
            timeline("Task queued"),
            timeline("Review failed: LLM external call failed: category=llm_service_unavailable retryable=true status=503")
        ));

        var result = service.getReviewDetail(521L);

        assertThat(result.failureCategory()).isEqualTo("llm_service_unavailable");
        assertThat(result.failureReason()).isEqualTo("LLM 服务暂时不可用");
    }

    @Test
    void getReviewDetailReturnsSummaryWithoutInitialDetailCollections() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        mockDetailPages(List.of(
            changedFile("repoguard-backend/src/main/resources/db/migration/V22__unsafe_change.sql", "ADD", 180, 20),
            changedFile("repoguard-backend/src/main/java/com/repoguard/agent/security/AuthTokenFilter.java", "MODIFY", 90, 15),
            changedFile("README.md", "MODIFY", 3, 1)
        ), List.of(
            finding(1L, "HIGH", "repoguard-backend/src/main/resources/db/migration/V22__unsafe_change.sql", 4, "DDL risk", "Add rollback plan"),
            finding(2L, "MEDIUM", "repoguard-backend/src/main/java/com/repoguard/agent/security/AuthTokenFilter.java", 42, "Auth bypass risk", "Add guard")
        ), List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline("Task queued")));

        var result = service.getReviewDetail(521L);

        assertThat(result.findings()).isEmpty();
        assertThat(result.changedFiles()).isEmpty();
        assertThat(result.missingTests()).isEmpty();
        assertThat(result.timeline()).isEmpty();
        assertThat(result.findingTotal()).isEqualTo(2);
        assertThat(result.changedFileTotal()).isEqualTo(3);
        assertThat(result.missingTestTotal()).isZero();
        assertThat(result.riskProfile().summary()).contains("3 个变更文件", "审查发现 2 条");
        assertThat(result.prSummary().summary()).contains("3 个变更文件、2 条审查发现");
        assertThat(result.prSummary().githubCommentBody()).contains("任务 #521");
        assertThat(result.riskProfile().recommendHumanReview()).isTrue();
        assertThat(result.riskProfile().signals()).contains("包含 1 条高危以上发现");
        assertThat(result.riskProfile().highRiskFiles()).isEmpty();
    }

    @Test
    void getReviewDetailParsesChunkedReviewSummary() {
        ReviewTask task = task();
        task.setLlmPromptSummary(
            "PR octocat/Hello-World#1; chunked=true; chunks=3; files=12; additions=240; deletions=18; "
                + "aggregateRisk=HIGH; aggregateFindings=7; failedChunks=1; chunkReasons=security,config,build"
        );
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        mockDetailPages(List.of(), List.of(), List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline("Task queued")));

        var result = service.getReviewDetail(521L);

        assertThat(result.chunkedReview().enabled()).isTrue();
        assertThat(result.chunkedReview().chunkCount()).isEqualTo(3);
        assertThat(result.chunkedReview().aggregateRisk()).isEqualTo("high");
        assertThat(result.chunkedReview().aggregateFindings()).isEqualTo(7);
        assertThat(result.chunkedReview().failedChunks()).isEqualTo(1);
        assertThat(result.chunkedReview().reasons()).containsExactly("security", "config", "build");
    }

    @Test
    void getReviewDetailDisablesChunkedReviewForPlainPromptSummary() {
        ReviewTask task = task();
        task.setLlmPromptSummary("PR octocat/Hello-World#1; files=2; additions=8; deletions=1");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        mockDetailPages(List.of(), List.of(), List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline("Task queued")));

        var result = service.getReviewDetail(521L);

        assertThat(result.chunkedReview().enabled()).isFalse();
        assertThat(result.chunkedReview().chunkCount()).isZero();
        assertThat(result.chunkedReview().failedChunks()).isZero();
        assertThat(result.chunkedReview().reasons()).isEmpty();
    }

    @Test
    void getReviewStatusReturnsLatestTimelineAndFailureSummary() {
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        failedTask.setStartedAt(LocalDateTime.of(2026, 6, 12, 10, 0));
        failedTask.setFinishedAt(LocalDateTime.of(2026, 6, 12, 10, 1, 12));
        when(reviewTaskMapper.selectById(521L)).thenReturn(failedTask);
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(
            timeline("Task queued"),
            timeline("Review failed: 403 Resource not accessible by integration")
        ));

        var result = service.getReviewStatus(521L);

        assertThat(result.id()).isEqualTo(521L);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.riskLevel()).isEqualTo("high");
        assertThat(result.llmStatus()).isEqualTo("failed");
        assertThat(result.updatedAt()).isEqualTo("2026-06-12 10:01:12");
        assertThat(result.latestTimeline().label()).startsWith("Review failed:");
        assertThat(result.latestTimeline().status()).isEqualTo("done");
        assertThat(result.failureCategory()).isEqualTo("github_permission_denied");
    }

    @Test
    void getReviewStatusRejectsMissingTask() {
        when(reviewTaskMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getReviewStatus(404L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review task not found: 404");

        verify(reviewTimelineMapper, never()).selectList(any());
    }

    @Test
    void triggerManualReviewReturnsExistingTaskWithoutPublishingDuplicateMessage() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(task());

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-1-llm-string-response",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.existing()).isTrue();
        assertThat(result.triggerSource()).isEqualTo("existing_reused");
        assertThat(result.message()).isEqualTo("Review task already exists");
        verify(reviewTaskMapper, never()).insertManualReviewOrReuse(any(ReviewTask.class));
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void triggerManualReviewStoresGithubPrPickerSourceForNewTask() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(522L);
            return 1;
        }).when(reviewTaskMapper).insertManualReviewOrReuse(any(ReviewTask.class));

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-2",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.existing()).isFalse();
        assertThat(result.source()).isEqualTo("github_pr_picker");
        assertThat(result.triggerSource()).isEqualTo("github_pr_picker");
        verify(reviewTaskMapper).insertManualReviewOrReuse(org.mockito.Mockito.argThat((ReviewTask task) ->
            "GITHUB_PR_PICKER".equals(task.getSource()) && "GITHUB_PR_PICKER".equals(task.getTriggerSource())
        ));
        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void triggerManualReviewPublishesMessageAfterTransactionCommit() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(522L);
            return 1;
        }).when(reviewTaskMapper).insertManualReviewOrReuse(any(ReviewTask.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.triggerManualReview(new ManualReviewRequest(
                "octocat",
                "Hello-World",
                2,
                "Smoke review",
                "public-pr-after-commit",
                "master",
                "github_pr_picker"
            ));

            assertThat(result.status()).isEqualTo("queued");
            assertThat(result.taskId()).isEqualTo(522L);
            verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(2);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

            ArgumentCaptor<ReviewTaskMessage> messageCaptor = ArgumentCaptor.forClass(ReviewTaskMessage.class);
            verify(reviewTaskPublisher).publish(messageCaptor.capture());
            assertThat(messageCaptor.getValue().taskId()).isEqualTo(522L);
            assertThat(messageCaptor.getValue().commit()).isEqualTo("public-pr-after-commit");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void triggerManualReviewReturnsExistingTaskWhenConcurrentInsertWins() {
        ReviewTask existing = task();
        existing.setStatus("QUEUED");
        when(reviewTaskMapper.selectOne(any())).thenReturn(null, existing);
        when(reviewTaskMapper.insertManualReviewOrReuse(any(ReviewTask.class))).thenReturn(0);

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-1-llm-string-response",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.existing()).isTrue();
        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.triggerSource()).isEqualTo("existing_reused");
        verify(reviewTaskMapper).insertManualReviewOrReuse(any(ReviewTask.class));
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void triggerManualReviewReusesExistingPendingCommitTask() {
        ReviewTask existing = task();
        existing.setCommitSha("pending");
        existing.setStatus("QUEUED");
        when(reviewTaskMapper.selectOne(any())).thenReturn(existing);

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.existing()).isTrue();
        assertThat(result.status()).isEqualTo("queued");
        verify(reviewTaskMapper, never()).insertManualReviewOrReuse(any(ReviewTask.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void triggerManualReviewMarksPublishFailedWhenMessageCannotBePublished() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(522L);
            return 1;
        }).when(reviewTaskMapper).insertManualReviewOrReuse(any(ReviewTask.class));
        doThrow(new MessagePublishException("publisher confirm timed out password=raw-password token=raw-token"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-publish-failed",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.status()).isEqualTo("publish_failed");

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(taskCaptor.getValue().getPublishAttempts()).isEqualTo(1);
        assertThat(taskCaptor.getValue().getNextPublishRetryAt()).isNotNull();
        assertThat(taskCaptor.getValue().getLastPublishError()).contains("publisher confirm timed out");
        assertThat(taskCaptor.getValue().getLastPublishError()).contains("password=****", "token=****");
        assertThat(taskCaptor.getValue().getLastPublishError()).doesNotContain("raw-password", "raw-token");

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper, org.mockito.Mockito.times(2)).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getAllValues().getLast().getLabel()).contains("Message publish failed");
        assertThat(timelineCaptor.getAllValues().getLast().getLabel()).contains("password=****", "token=****");
        assertThat(timelineCaptor.getAllValues().getLast().getLabel()).doesNotContain("raw-password", "raw-token");
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 50, 100})
    void triggerManualReviewKeepsConcurrentIdempotencyForSamePrAndCommit(int concurrency) throws Exception {
        CountingManualReviewIdempotencyCoordinator coordinator = new CountingManualReviewIdempotencyCoordinator(concurrency - 1);
        ReviewTaskAfterCommitPublisher afterCommitPublisher = reviewTaskAfterCommitPublisher();
        ReviewTaskCommandServiceImpl commandService = new ReviewTaskCommandServiceImpl(
            humanReviewCommandService(),
            reviewTaskRetryService(afterCommitPublisher),
            manualReviewCreationService(coordinator, afterCommitPublisher)
        );
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(9001L);
            coordinator.awaitDuplicateRegistrations();
            return 1;
        }).when(reviewTaskMapper).insertManualReviewOrReuse(any(ReviewTask.class));

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ManualReviewResult>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(ready);
                await(start);
                var response = commandService.triggerManualReview(new ManualReviewRequest(
                    "octocat",
                    "Hello-World",
                    42,
                    "Concurrent review",
                    "same-commit",
                    "master",
                    "github_pr_picker"
                ));
                return new ManualReviewResult(response.taskId(), response.existing(), response.status());
            }, executor));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<ManualReviewResult> results = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        executor.shutdownNow();

        assertThat(results).hasSize(concurrency);
        assertThat(results).allMatch(result -> result.taskId().equals(9001L));
        assertThat(results).filteredOn(result -> !result.existing()).hasSize(1);
        assertThat(results).filteredOn(ManualReviewResult::existing).hasSize(concurrency - 1);
        assertThat(results).allMatch(result -> result.status().equals("queued"));
        verify(reviewTaskMapper, org.mockito.Mockito.times(1)).insertManualReviewOrReuse(any(ReviewTask.class));
        verify(reviewTimelineMapper, org.mockito.Mockito.times(1)).insert(any(ReviewTimeline.class));
        verify(reviewTaskPublisher, org.mockito.Mockito.times(1)).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void retryReviewQueuesFailedTaskAndPublishesMessage() {
        ReviewTask task = task();
        task.setStatus("FAILED");
        task.setRiskLevel("HIGH");
        task.setMqRetries(2);
        task.setLlmStatus("FAILED");
        ReviewTimeline latestTimeline = new ReviewTimeline();
        latestTimeline.setSortOrder(5);
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latestTimeline);

        var result = service.retryReview(521L);

        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.retryCount()).isEqualTo(3);

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("QUEUED");
        assertThat(taskCaptor.getValue().getLlmStatus()).isEqualTo("PENDING");
        assertThat(taskCaptor.getValue().getRiskLevel()).isEqualTo("INFO");
        assertThat(taskCaptor.getValue().getMqRetries()).isEqualTo(3);

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).isEqualTo("Retry queued");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("CURRENT");
        assertThat(timelineCaptor.getValue().getSortOrder()).isEqualTo(6);

        ArgumentCaptor<ReviewTaskMessage> messageCaptor = ArgumentCaptor.forClass(ReviewTaskMessage.class);
        verify(reviewTaskPublisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().taskId()).isEqualTo(521L);
        assertThat(messageCaptor.getValue().commit()).isEqualTo("public-pr-1-llm-string-response");
    }

    @Test
    void retryReviewMarksPublishFailedWhenMessageCannotBePublished() {
        ReviewTask task = task();
        task.setStatus("FAILED");
        task.setRiskLevel("HIGH");
        task.setMqRetries(2);
        task.setLlmStatus("FAILED");
        ReviewTimeline latestTimeline = new ReviewTimeline();
        latestTimeline.setSortOrder(5);
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latestTimeline);
        doThrow(new MessagePublishException("unroutable"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));

        var result = service.retryReview(521L);

        assertThat(result.status()).isEqualTo("publish_failed");
        assertThat(result.retryCount()).isEqualTo(3);

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper, org.mockito.Mockito.times(2)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().getLast().getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(taskCaptor.getAllValues().getLast().getPublishAttempts()).isEqualTo(1);
        assertThat(taskCaptor.getAllValues().getLast().getLastPublishError()).contains("unroutable");
    }

    @Test
    void retryReviewRejectsNonFailedTask() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());

        assertThatThrownBy(() -> service.retryReview(521L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only failed review tasks can be retried");

        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    private void mockDetailPages(
        List<ChangedFile> changedFiles,
        List<ReviewFinding> findings,
        List<ReviewFinding> missingTests
    ) {
        when(changedFileMapper.selectPage(any(), any())).thenReturn(page(changedFiles));
        when(reviewFindingMapper.selectPage(any(), any())).thenReturn(page(findings), page(missingTests));
        when(changedFileMapper.selectCount(any())).thenReturn((long) changedFiles.size());
        when(reviewFindingMapper.selectCount(any())).thenReturn((long) findings.size(), (long) missingTests.size());
        when(reviewFindingMapper.selectFindingSeverityCounts(521L)).thenReturn(severityCounts(findings));
    }

    private FindingSeverityCountsDto severityCounts(List<ReviewFinding> findings) {
        long critical = findings.stream().filter(finding -> "CRITICAL".equalsIgnoreCase(finding.getSeverity())).count();
        long high = findings.stream().filter(finding -> "HIGH".equalsIgnoreCase(finding.getSeverity())).count();
        long medium = findings.stream().filter(finding -> "MEDIUM".equalsIgnoreCase(finding.getSeverity())).count();
        long low = findings.stream().filter(finding -> "LOW".equalsIgnoreCase(finding.getSeverity())).count();
        long known = critical + high + medium + low;
        return new FindingSeverityCountsDto(critical, high, medium, low, findings.size() - known);
    }

    private <T> Page<T> page(List<T> records) {
        Page<T> page = Page.of(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(1);
        task.setTitle("Smoke review");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("public-pr-1-llm-string-response");
        task.setBranchName("master");
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setMqRetries(0);
        task.setLlmStatus("COMPLETED");
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/1");
        task.setSource("GITHUB_PR_PICKER");
        task.setTriggerSource("GITHUB_PR_PICKER");
        task.setCreatedAt(LocalDateTime.now());
        task.setDurationSeconds(37);
        return task;
    }

    private ChangedFile changedFile(String path, String changeType) {
        return changedFile(path, changeType, 6, 1);
    }

    private ChangedFile changedFile(String path, String changeType, int additions, int deletions) {
        ChangedFile file = new ChangedFile();
        file.setTaskId(521L);
        file.setFilePath(path);
        file.setChangeType(changeType);
        file.setAdditions(additions);
        file.setDeletions(deletions);
        return file;
    }

    private ReviewFinding finding(Long id, String severity, String file, Integer line, String message, String recommendation) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        finding.setTaskId(521L);
        finding.setCategory("FINDING");
        finding.setSeverity(severity);
        finding.setSource("LLM");
        finding.setRuleId("LLM");
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage(message);
        finding.setRecommendation(recommendation);
        return finding;
    }

    private ReviewTimeline timeline(String label) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(521L);
        timeline.setLabel(label);
        timeline.setEventTime(LocalDateTime.of(2026, 6, 9, 12, 0));
        timeline.setStatus(label.startsWith("Review failed") ? "FAILED" : "DONE");
        timeline.setSortOrder(label.startsWith("Review failed") ? 5 : 1);
        return timeline;
    }

    private GithubIntegrationSettings githubSettings(
        String owner,
        String repository,
        String status,
        String token,
        String lastError
    ) {
        return new GithubIntegrationSettings(
            "GITHUB",
            status,
            "https://api.github.com",
            token,
            lastError,
            owner,
            repository,
            1L
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent review test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent review test latch", ex);
        }
    }

    private record ManualReviewResult(
        Long taskId,
        boolean existing,
        String status
    ) {
    }

    private static class CountingManualReviewIdempotencyCoordinator extends ManualReviewIdempotencyCoordinator {

        private final CountDownLatch duplicateRegistrations;

        CountingManualReviewIdempotencyCoordinator(int expectedDuplicates) {
            super(org.mockito.Mockito.mock(ScheduledExecutorService.class));
            this.duplicateRegistrations = new CountDownLatch(expectedDuplicates);
        }

        @Override
        public CompletableFuture<ReviewTask> registerOwner(
            String idempotencyKey,
            CompletableFuture<ReviewTask> ownerFuture
        ) {
            CompletableFuture<ReviewTask> existingFuture = super.registerOwner(idempotencyKey, ownerFuture);
            if (existingFuture != null) {
                duplicateRegistrations.countDown();
            }
            return existingFuture;
        }

        void awaitDuplicateRegistrations() {
            try {
                duplicateRegistrations.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for duplicate manual review registrations", ex);
            }
        }
    }
}
