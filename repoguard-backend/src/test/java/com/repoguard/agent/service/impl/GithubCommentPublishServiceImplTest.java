package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentWritebackCheck;
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
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.GithubCommentPreviewService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    private final GithubCommentPreviewService previewService = org.mockito.Mockito.mock(
        GithubCommentPreviewService.class
    );
    private final GithubCommentPublishServiceImpl service = new GithubCommentPublishServiceImpl(
        reviewTaskMapper,
        publicationMapper,
        batchMapper,
        batchItemMapper,
        githubPullRequestClient,
        null,
        null,
        new ReviewTaskStateMachine(),
        new GithubWritebackFailureClassifier(),
        previewService
    );

    @Test
    void publishGithubCommentsSendsCommentableDraftsAndRecordsBatchHistory() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(previewService.getPreview(521L)).thenReturn(preview(List.of(
            item(null, "PR summary", null, "pull_request", true, false),
            item(1L, "Use logger", 8, "line", true, false),
            item(2L, "Already published", 9, "line", false, true)
        )));
        when(publicationMapper.selectOne(any())).thenReturn(null);
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(null, "PR summary", null, "pull_request", true, "published",
                "GitHub comment published", "https://github.com/comment/summary", 100L),
            new GithubReviewCommentResult(1L, "README.md", 8, "line", true, "published",
                "GitHub comment published", "https://github.com/comment/1", 101L)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.succeededCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("status")
            .containsExactly("published", "published", "already_published");
        verify(publicationMapper, org.mockito.Mockito.times(2)).insert(any(GithubCommentPublication.class));
        verify(batchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(batchItemMapper, org.mockito.Mockito.times(3)).insert(any(GithubCommentPublicationBatchItem.class));
    }

    @Test
    void publishGithubCommentsClassifiesGithubPermissionFailure() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(previewService.getPreview(521L)).thenReturn(preview(List.of(
            item(1L, "Use logger", 8, "line", true, false)
        )));
        when(publicationMapper.selectOne(any())).thenReturn(null);
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(1L, "README.md", 8, "line", false, "failed",
                "403 Resource not accessible by integration", null, null)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.items().getFirst().failureReason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.items().getFirst().failureSuggestion()).contains("评论权限");
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

        verify(previewService, never()).getPreview(any());
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
    }

    private GithubCommentPreviewResponse preview(List<GithubCommentPreviewItem> items) {
        return new GithubCommentPreviewResponse(
            521L,
            1,
            "https://github.com/octocat/Hello-World/pull/1",
            new GithubCommentWritebackCheck(
                "ready",
                "success",
                "octocat",
                "Hello-World",
                "octocat",
                "Hello-World",
                true,
                true,
                true,
                null,
                List.of()
            ),
            2,
            (int) items.stream().filter(GithubCommentPreviewItem::commentable).count(),
            (int) items.stream().filter(item -> !item.commentable()).count(),
            items
        );
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
}
