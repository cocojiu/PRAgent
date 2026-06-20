package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentDraftPublisherTest {

    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(
        GithubPullRequestClient.class
    );
    private final GithubCommentPublicationRecorder publicationRecorder = org.mockito.Mockito.mock(
        GithubCommentPublicationRecorder.class
    );
    private final GithubCommentDraftPublisher publisher = new GithubCommentDraftPublisher(
        githubPullRequestClient,
        publicationRecorder,
        new GithubWritebackFailureClassifier()
    );

    @Test
    void publishReturnsEmptyItemsWhenDraftsAreEmpty() {
        List<com.repoguard.agent.dto.GithubCommentPublishItem> result = publisher.publish(task(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void publishConvertsGithubClientResultsToPublishItemsAndRecordsPublication() {
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(11L, "src/App.java", 42, "line", true, "published",
                "GitHub comment published", "https://github.com/comment/11", 1001L)
        ));
        when(publicationRecorder.recordPublication(any(), any())).thenReturn(publication());

        var result = publisher.publish(task(), List.of(
            new GithubReviewCommentDraft(11L, "src/App.java", 42, "body", "line")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().findingId()).isEqualTo(11L);
        assertThat(result.getFirst().success()).isTrue();
        assertThat(result.getFirst().status()).isEqualTo("published");
        assertThat(result.getFirst().url()).isEqualTo("https://github.com/comment/11");
        assertThat(result.getFirst().githubCommentId()).isEqualTo(1001L);
        assertThat(result.getFirst().publishedAt()).isEqualTo("2026-06-20 09:45:00");
        verify(publicationRecorder).recordPublication(any(), any(GithubReviewCommentResult.class));
    }

    @Test
    void publishConvertsClientExceptionToFailedItemsForEveryDraft() {
        when(githubPullRequestClient.publishPullRequestComments(any(), any()))
            .thenThrow(new RuntimeException("403 Resource not accessible by integration"));
        when(publicationRecorder.recordPublication(any(), any())).thenReturn(failedPublication());

        var result = publisher.publish(task(), List.of(
            new GithubReviewCommentDraft(11L, "src/App.java", 42, "body", "line"),
            new GithubReviewCommentDraft(null, "PR summary", null, "summary", "pull_request")
        ));

        assertThat(result).hasSize(2);
        assertThat(result).extracting("success").containsExactly(false, false);
        assertThat(result).extracting("status").containsExactly("failed", "failed");
        assertThat(result.getFirst().message()).isEqualTo("403 Resource not accessible by integration");
        assertThat(result.getFirst().failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.getFirst().publishedAt()).isNull();
    }

    @Test
    void publishUsesExceptionClassNameWhenMessageIsBlank() {
        when(githubPullRequestClient.publishPullRequestComments(any(), any()))
            .thenThrow(new RuntimeException(" "));
        when(publicationRecorder.recordPublication(any(), any())).thenReturn(failedPublication());

        var result = publisher.publish(task(), List.of(
            new GithubReviewCommentDraft(11L, "src/App.java", 42, "body", "line")
        ));

        assertThat(result.getFirst().message()).isEqualTo("RuntimeException");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(1);
        return task;
    }

    private GithubCommentPublication publication() {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 20, 9, 45));
        return publication;
    }

    private GithubCommentPublication failedPublication() {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setPublishedAt(null);
        return publication;
    }
}
