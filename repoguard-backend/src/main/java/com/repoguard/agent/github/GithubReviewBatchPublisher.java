package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * Publishes inline drafts as one GitHub review and reconciles returned comment identities.
 */
final class GithubReviewBatchPublisher {

    private final GithubCommentPublicationGateway publicationGateway;
    private final GithubIntegrationHealthReporter healthReporter;

    GithubReviewBatchPublisher(
        GithubCommentPublicationGateway publicationGateway,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this.publicationGateway = Objects.requireNonNull(publicationGateway, "publicationGateway");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
    }

    List<GithubReviewCommentResult> publish(
        String reviewUrl,
        List<GithubReviewCommentDraft> lineDrafts,
        String commitSha,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        GithubReviewResponse review = publicationGateway.publishReview(
            reviewUrl,
            lineDrafts,
            commitSha,
            settings,
            resilience
        );
        List<GithubReviewCommentDetail> unmatched = new ArrayList<>(
            listReviewCommentsQuietly(reviewUrl, review, settings, resilience)
        );
        String reviewUrlFallback = review == null ? null : review.htmlUrl();
        List<GithubReviewCommentResult> results = lineDrafts.stream()
            .map(draft -> publishedResult(draft, takeMatchingReviewComment(unmatched, draft), reviewUrlFallback))
            .toList();
        healthReporter.markChecked(settings, null);
        return results;
    }

    boolean isValidationFailure(RuntimeException ex) {
        return publicationGateway.isReviewValidationFailure(ex);
    }

    private List<GithubReviewCommentDetail> listReviewCommentsQuietly(
        String reviewUrl,
        GithubReviewResponse review,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        if (review == null || review.id() == null) {
            return List.of();
        }
        String url = reviewUrl + "/" + review.id() + "/comments?per_page=100";
        try {
            GithubReviewCommentDetail[] comments = publicationGateway.listReviewComments(url, settings, resilience);
            return comments == null ? List.of() : Arrays.asList(comments);
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            healthReporter.recordExternalFailure(classified);
            return List.of();
        }
    }

    private GithubReviewCommentResult publishedResult(
        GithubReviewCommentDraft draft,
        GithubReviewCommentDetail matched,
        String reviewUrlFallback
    ) {
        return new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            GithubCommentTargetType.LINE.code(),
            true,
            GithubCommentPublicationStatus.PUBLISHED.code(),
            "GitHub comment published",
            matched != null && StringUtils.hasText(matched.htmlUrl()) ? matched.htmlUrl() : reviewUrlFallback,
            matched == null ? null : matched.id()
        );
    }

    private GithubReviewCommentDetail takeMatchingReviewComment(
        List<GithubReviewCommentDetail> comments,
        GithubReviewCommentDraft draft
    ) {
        GithubReviewCommentDetail matched = null;
        for (GithubReviewCommentDetail comment : comments) {
            if (!matchesDraftLocation(comment, draft)) {
                continue;
            }
            if (Objects.equals(comment.body(), draft.body())) {
                matched = comment;
                break;
            }
            if (matched == null) {
                matched = comment;
            }
        }
        if (matched != null) {
            comments.remove(matched);
        }
        return matched;
    }

    private boolean matchesDraftLocation(GithubReviewCommentDetail comment, GithubReviewCommentDraft draft) {
        return Objects.equals(comment.path(), draft.path())
            && (Objects.equals(comment.line(), draft.line()) || Objects.equals(comment.originalLine(), draft.line()));
    }
}
