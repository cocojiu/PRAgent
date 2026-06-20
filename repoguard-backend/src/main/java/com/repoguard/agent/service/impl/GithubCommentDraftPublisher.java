package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.github.GithubWritebackFailureClassifier.FailureSummary;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Publishes GitHub comment drafts and converts client results into persisted publish items.
 */
@Component
public class GithubCommentDraftPublisher {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GithubPullRequestClient githubPullRequestClient;
    private final GithubCommentPublicationRecorder publicationRecorder;
    private final GithubWritebackFailureClassifier writebackFailureClassifier;

    public GithubCommentDraftPublisher(
        GithubPullRequestClient githubPullRequestClient,
        GithubCommentPublicationRecorder publicationRecorder,
        GithubWritebackFailureClassifier writebackFailureClassifier
    ) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.publicationRecorder = publicationRecorder;
        this.writebackFailureClassifier = writebackFailureClassifier;
    }

    public List<GithubCommentPublishItem> publish(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
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
                .map(draft -> toGithubCommentPublishItem(task.getId(), failedResult(draft, message)))
                .toList();
        }
    }

    private GithubReviewCommentResult failedResult(GithubReviewCommentDraft draft, String message) {
        return new GithubReviewCommentResult(
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
    }

    private GithubCommentPublishItem toGithubCommentPublishItem(Long taskId, GithubReviewCommentResult result) {
        GithubCommentPublication publication = publicationRecorder.recordPublication(taskId, result);
        FailureSummary failureSummary = writebackFailureClassifier.classify(
            result.status(),
            result.success(),
            result.message()
        );
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
}
