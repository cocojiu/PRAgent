package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Preserves inline findings as one traceable PR comment after the pull-request head changes.
 */
final class GithubSupersededSummaryPublisher {

    private static final int MAX_SUPERSEDED_SUMMARY_LENGTH = 60_000;

    private final GithubCommentPublicationGateway publicationGateway;
    private final GithubIntegrationHealthReporter healthReporter;

    GithubSupersededSummaryPublisher(
        GithubCommentPublicationGateway publicationGateway,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this.publicationGateway = Objects.requireNonNull(publicationGateway, "publicationGateway");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
    }

    GithubCommentBatchResult publish(
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> lineDrafts,
        String prCommentUrl,
        String expectedCommitSha,
        String currentHeadSha,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        try {
            GithubCommentResponse response = publicationGateway.publishPullRequestComment(
                prCommentUrl,
                summaryBody(lineDrafts, expectedCommitSha, currentHeadSha),
                settings,
                resilience
            );
            List<GithubReviewCommentResult> results = lineDrafts.stream()
                .map(draft -> publishedResult(draft, expectedCommitSha, response))
                .toList();
            healthReporter.markChecked(settings, null);
            return new GithubCommentBatchResult(results, 0);
        } catch (RuntimeException ex) {
            return failedBatch(startedAt, settings, lineDrafts, ex);
        }
    }

    String summaryBody(
        List<GithubReviewCommentDraft> lineDrafts,
        String expectedCommitSha,
        String currentHeadSha
    ) {
        StringBuilder body = new StringBuilder()
            .append("## RepoGuard review comments (superseded)\n\n")
            .append("The pull request head changed before inline comments were published. ")
            .append("To avoid attaching findings to the wrong code, the original line comments are recorded here.\n\n")
            .append("- Reviewed commit: `").append(expectedCommitSha).append("`\n")
            .append("- Current head: `").append(currentHeadSha).append("`\n\n");
        int omitted = 0;
        for (GithubReviewCommentDraft draft : lineDrafts) {
            String section = "### `" + draft.path() + ":" + draft.line() + "`\n\n"
                + draft.body()
                + "\n\n";
            if (body.length() + section.length() > MAX_SUPERSEDED_SUMMARY_LENGTH) {
                omitted++;
                continue;
            }
            body.append(section);
        }
        if (omitted > 0) {
            body.append("_").append(omitted).append(" additional comment(s) omitted because of GitHub size limits._\n");
        }
        return body.toString();
    }

    private GithubReviewCommentResult publishedResult(
        GithubReviewCommentDraft draft,
        String expectedCommitSha,
        GithubCommentResponse response
    ) {
        return new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            GithubCommentTargetType.PULL_REQUEST.code(),
            true,
            GithubCommentPublicationStatus.DOWNGRADED_TO_PR_COMMENT.code(),
            "Pull request head changed; line comment for commit "
                + shortCommit(expectedCommitSha)
                + " was published in a traceable PR summary",
            response == null ? null : response.htmlUrl(),
            response == null ? null : response.id()
        );
    }

    private GithubCommentBatchResult failedBatch(
        LocalDateTime startedAt,
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> drafts,
        RuntimeException ex
    ) {
        RuntimeException classified = ExternalCallErrorClassifier.github(ex);
        healthReporter.recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
        healthReporter.recordExternalFailure(classified);
        String message = healthReporter.conciseError(classified);
        List<GithubReviewCommentResult> results = drafts.stream()
            .map(draft -> failedResult(draft, message))
            .toList();
        healthReporter.markChecked(settings, message);
        return new GithubCommentBatchResult(results, drafts.size());
    }

    private GithubReviewCommentResult failedResult(GithubReviewCommentDraft draft, String message) {
        return new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            draft.targetType(),
            false,
            GithubCommentPublicationStatus.FAILED.code(),
            message,
            null,
            null
        );
    }

    private String shortCommit(String commitSha) {
        return commitSha.length() <= 12 ? commitSha : commitSha.substring(0, 12);
    }
}
