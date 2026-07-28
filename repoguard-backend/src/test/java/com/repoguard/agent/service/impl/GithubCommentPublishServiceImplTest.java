package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.impl.GithubCommentPublishCandidateLoader.GithubCommentPublishCandidateOverview;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GithubCommentPublishServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final GithubCommentPublicationMapper publicationMapper = org.mockito.Mockito.mock(
        GithubCommentPublicationMapper.class
    );
    private final GithubCommentPublicationBatchMapper batchMapper = org.mockito.Mockito.mock(
        GithubCommentPublicationBatchMapper.class
    );
    private final GithubCommentPublicationBatchItemMapper batchItemMapper = org.mockito.Mockito.mock(
        GithubCommentPublicationBatchItemMapper.class
    );
    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(
        GithubPullRequestClient.class
    );
    private final GithubCommentPublishCandidateLoader publishCandidateLoader = org.mockito.Mockito.mock(
        GithubCommentPublishCandidateLoader.class
    );
    private final GithubCommentPublishMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(
        GithubCommentPublishMetricsRecorder.class
    );
    private final NotificationDispatchService notificationDispatchService = org.mockito.Mockito.mock(
        NotificationDispatchService.class
    );
    private final RecordingExecutor publishExecutor = new RecordingExecutor();
    private final GithubCommentPublicationRecorder publicationRecorder = new GithubCommentPublicationRecorder(
        publicationMapper,
        batchMapper,
        batchItemMapper
    );
    private final GithubWritebackFailureClassifier writebackFailureClassifier =
        new GithubWritebackFailureClassifier();
    private final GithubCommentPublishServiceImpl service = new GithubCommentPublishServiceImpl(
        reviewTaskMapper,
        metricsRecorder,
        notificationDispatchService,
        publishExecutor,
        publishCandidateLoader,
        new GithubCommentPublishGuard(new ReviewTaskStateMachine()),
        new GithubCommentPublishPlanBuilder(),
        new GithubCommentDraftPublisher(
            githubPullRequestClient,
            publicationRecorder,
            writebackFailureClassifier
        ),
        publicationRecorder
    );

    @BeforeEach
    void assignBatchIds() {
        org.mockito.Mockito.doAnswer(invocation -> {
            GithubCommentPublicationBatch batch = invocation.getArgument(0);
            batch.setId(99L);
            return 1;
        }).when(batchMapper).insert(any(GithubCommentPublicationBatch.class));
        when(batchMapper.update(any())).thenReturn(1);
    }

    @Test
    void publishGithubCommentsSendsCommentableDraftsAndRecordsBatchHistory() {
        ReviewTask task = task();
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(publishCandidateLoader.loadOverview(task)).thenReturn(overview(
            2,
            item(null, "PR summary", null, "pull_request", true, false)
        ));
        when(publishCandidateLoader.loadFindingCandidates(521L, 0L, 49)).thenReturn(List.of(
            item(1L, "Use logger", 8, "line", true, false)
        ));
        when(publicationMapper.selectOne(any())).thenReturn(null);
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(null, "PR summary", null, "pull_request", true, "published",
                "GitHub comment published", "https://github.com/comment/summary", 100L),
            new GithubReviewCommentResult(1L, "README.md", 8, "line", true, "published",
                "GitHub comment published", "https://github.com/comment/1", 101L)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.batchId()).isEqualTo(99L);
        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.attemptedCount()).isZero();
        assertThat(result.items()).isEmpty();
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());

        publishExecutor.runPending();

        verify(publicationMapper, org.mockito.Mockito.times(2)).insert(any(GithubCommentPublication.class));
        verify(batchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(batchMapper, org.mockito.Mockito.times(2)).update(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GithubCommentPublicationBatchItem>> historyCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(batchItemMapper).insertBatch(historyCaptor.capture());
        assertThat(historyCaptor.getValue()).hasSize(2);
        verify(metricsRecorder).recordItems(2, 0, 1);
        verify(metricsRecorder).recordDuration(any(LocalDateTime.class), org.mockito.Mockito.eq(false));
        ArgumentCaptor<GithubCommentPublishResponse> notificationResponseCaptor =
            ArgumentCaptor.forClass(GithubCommentPublishResponse.class);
        verify(notificationDispatchService).githubCommentsPublished(
            any(ReviewTask.class),
            notificationResponseCaptor.capture(),
            org.mockito.Mockito.eq(99L)
        );
        assertThat(notificationResponseCaptor.getValue().attemptedCount()).isEqualTo(2);
        assertThat(notificationResponseCaptor.getValue().succeededCount()).isEqualTo(2);
        assertThat(notificationResponseCaptor.getValue().failedCount()).isZero();
    }

    @Test
    void publishGithubCommentsClassifiesGithubPermissionFailure() {
        ReviewTask task = task();
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(publishCandidateLoader.loadOverview(task)).thenReturn(overview(1, null));
        when(publishCandidateLoader.loadFindingCandidates(521L, 0L, 50)).thenReturn(List.of(
            item(1L, "Use logger", 8, "line", true, false)
        ));
        when(publicationMapper.selectOne(any())).thenReturn(null);
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(1L, "README.md", 8, "line", false, "failed",
                "403 Resource not accessible by integration", null, null)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.status()).isEqualTo("queued");
        publishExecutor.runPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GithubCommentPublicationBatchItem>> itemCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(batchItemMapper).insertBatch(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo("failed");
            assertThat(item.getMessage()).contains("403 Resource not accessible");
        });
        verify(metricsRecorder).recordItems(0, 1, 1);
        verify(metricsRecorder).recordDuration(any(LocalDateTime.class), org.mockito.Mockito.eq(true));
    }

    @Test
    void constructorRejectsMissingMetricsRecorder() {
        assertThatThrownBy(() -> new GithubCommentPublishServiceImpl(
            reviewTaskMapper,
            null,
            notificationDispatchService,
            publishExecutor,
            publishCandidateLoader,
            new GithubCommentPublishGuard(new ReviewTaskStateMachine()),
            new GithubCommentPublishPlanBuilder(),
            new GithubCommentDraftPublisher(
                githubPullRequestClient,
                publicationRecorder,
                writebackFailureClassifier
            ),
            publicationRecorder
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metricsRecorder");
    }

    @Test
    void constructorRejectsMissingNotificationDispatchService() {
        assertThatThrownBy(() -> new GithubCommentPublishServiceImpl(
            reviewTaskMapper,
            metricsRecorder,
            null,
            publishExecutor,
            publishCandidateLoader,
            new GithubCommentPublishGuard(new ReviewTaskStateMachine()),
            new GithubCommentPublishPlanBuilder(),
            new GithubCommentDraftPublisher(
                githubPullRequestClient,
                publicationRecorder,
                writebackFailureClassifier
            ),
            publicationRecorder
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("notificationDispatchService");
    }

    @Test
    void publishGithubCommentsRejectsPendingHumanReviewTask() {
        ReviewTask task = task();
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);

        assertThatThrownBy(() -> service.publishGithubComments(521L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Human review");

        verify(publishCandidateLoader, never()).loadOverview(any());
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
    }

    @Test
    void publishGithubCommentsKeepsQueuedBatchWhenExecutorRejects() {
        ReviewTask task = task();
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        GithubCommentPublishServiceImpl rejectingService = new GithubCommentPublishServiceImpl(
            reviewTaskMapper,
            metricsRecorder,
            notificationDispatchService,
            rejectingExecutor,
            publishCandidateLoader,
            new GithubCommentPublishGuard(new ReviewTaskStateMachine()),
            new GithubCommentPublishPlanBuilder(),
            new GithubCommentDraftPublisher(
                githubPullRequestClient,
                publicationRecorder,
                writebackFailureClassifier
            ),
            publicationRecorder
        );
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(publishCandidateLoader.loadOverview(task)).thenReturn(overview(1, null));

        var result = rejectingService.publishGithubComments(521L);

        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.batchId()).isEqualTo(99L);
        verify(batchMapper).update(any());
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
    }

    private GithubCommentPublishCandidateOverview overview(int totalFindings, GithubCommentPreviewItem prSummaryCandidate) {
        return new GithubCommentPublishCandidateOverview(totalFindings, prSummaryCandidate);
    }

    private GithubCommentPreviewItem item(
        Long findingId,
        String message,
        Integer line,
        String targetType,
        boolean commentable,
        boolean published
    ) {
        return new GithubCommentPreviewItem(
            findingId,
            "LOW",
            findingId == null ? "PR summary" : "README.md",
            line,
            message,
            "Replace stdout",
            "RepoGuard comment body",
            commentable,
            targetType,
            published ? "GitHub comment already published" : null,
            published,
            published ? "published" : null,
            published ? "https://github.com/comment/already" : null,
            published ? "GitHub comment published" : null,
            published ? "2026-06-18 11:00:00" : null
        );
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(1);
        task.setTitle("Publish review");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("abc123");
        task.setBranchName("main");
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setMqRetries(0);
        task.setLlmStatus("COMPLETED");
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/1");
        task.setSource("GITHUB_PR_PICKER");
        task.setTriggerSource("GITHUB_PR_PICKER");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
        task.setDurationSeconds(37);
        return task;
    }

    private static final class RecordingExecutor implements Executor {
        private Runnable pendingCommand;

        @Override
        public void execute(Runnable command) {
            pendingCommand = command;
        }

        private void runPending() {
            assertThat(pendingCommand).isNotNull();
            Runnable command = pendingCommand;
            pendingCommand = null;
            command.run();
        }
    }
}
