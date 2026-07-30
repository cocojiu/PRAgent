package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Falls back from a rejected review batch to fenced, per-comment GitHub publication.
 */
final class GithubLineCommentFallbackPublisher {

    private final GithubCommentPublicationGateway publicationGateway;
    private final GithubPullRequestHeadReader headReader;
    private final GithubSupersededSummaryPublisher supersededSummaryPublisher;
    private final GithubIntegrationHealthReporter healthReporter;

    GithubLineCommentFallbackPublisher(
        GithubCommentPublicationGateway publicationGateway,
        GithubPullRequestHeadReader headReader,
        GithubSupersededSummaryPublisher supersededSummaryPublisher,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this.publicationGateway = Objects.requireNonNull(publicationGateway, "publicationGateway");
        this.headReader = Objects.requireNonNull(headReader, "headReader");
        this.supersededSummaryPublisher = Objects.requireNonNull(
            supersededSummaryPublisher,
            "supersededSummaryPublisher"
        );
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
    }

    GithubCommentBatchResult publish(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        List<GithubReviewCommentDraft> lineDrafts,
        String lineCommentUrl,
        String prCommentUrl,
        String commitSha,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        List<GithubReviewCommentResult> results = new ArrayList<>();
        int failedCount = 0;
        for (int position = 0; position < lineDrafts.size(); position++) {
            GithubReviewCommentDraft draft = lineDrafts.get(position);
            try {
                String currentHeadSha = fetchCurrentHeadSha(
                    settings,
                    baseUrl,
                    owner,
                    repository,
                    task,
                    resilience
                );
                if (!sameCommit(commitSha, currentHeadSha)) {
                    return appendSupersededResults(
                        results,
                        failedCount,
                        settings,
                        lineDrafts.subList(position, lineDrafts.size()),
                        prCommentUrl,
                        commitSha,
                        currentHeadSha,
                        resilience,
                        startedAt
                    );
                }
                GithubCommentTargetType actualTargetType = GithubCommentTargetType.LINE;
                GithubCommentResponse response;
                try {
                    response = publicationGateway.publishLineComment(
                        lineCommentUrl,
                        draft,
                        commitSha,
                        settings,
                        resilience
                    );
                } catch (RuntimeException ex) {
                    if (!publicationGateway.isUnresolvableLineComment(ex)) {
                        throw ex;
                    }
                    currentHeadSha = fetchCurrentHeadSha(
                        settings,
                        baseUrl,
                        owner,
                        repository,
                        task,
                        resilience
                    );
                    if (!sameCommit(commitSha, currentHeadSha)) {
                        return appendSupersededResults(
                            results,
                            failedCount,
                            settings,
                            lineDrafts.subList(position, lineDrafts.size()),
                            prCommentUrl,
                            commitSha,
                            currentHeadSha,
                            resilience,
                            startedAt
                        );
                    }
                    actualTargetType = GithubCommentTargetType.PULL_REQUEST;
                    response = publicationGateway.publishPullRequestComment(
                        prCommentUrl,
                        draft.body(),
                        settings,
                        resilience
                    );
                }
                results.add(publishedResult(draft, actualTargetType, response));
                healthReporter.markChecked(settings, null);
            } catch (RuntimeException ex) {
                failedCount++;
                results.add(failedResult(startedAt, settings, draft, ex));
            }
        }
        return new GithubCommentBatchResult(List.copyOf(results), failedCount);
    }

    private GithubCommentBatchResult appendSupersededResults(
        List<GithubReviewCommentResult> publishedResults,
        int failedCount,
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> remainingDrafts,
        String prCommentUrl,
        String expectedCommitSha,
        String currentHeadSha,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        GithubCommentBatchResult superseded = supersededSummaryPublisher.publish(
            settings,
            remainingDrafts,
            prCommentUrl,
            expectedCommitSha,
            currentHeadSha,
            resilience,
            startedAt
        );
        publishedResults.addAll(superseded.results());
        return new GithubCommentBatchResult(
            List.copyOf(publishedResults),
            failedCount + superseded.failedCount()
        );
    }

    private GithubReviewCommentResult publishedResult(
        GithubReviewCommentDraft draft,
        GithubCommentTargetType actualTargetType,
        GithubCommentResponse response
    ) {
        boolean downgradedToPrComment = actualTargetType.isPullRequest();
        return new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            actualTargetType.code(),
            true,
            downgradedToPrComment
                ? GithubCommentPublicationStatus.DOWNGRADED_TO_PR_COMMENT.code()
                : GithubCommentPublicationStatus.PUBLISHED.code(),
            downgradedToPrComment
                ? "GitHub line comment could not be resolved; published as PR comment"
                : "GitHub comment published",
            response == null ? null : response.htmlUrl(),
            response == null ? null : response.id()
        );
    }

    private GithubReviewCommentResult failedResult(
        LocalDateTime startedAt,
        GithubIntegrationSettings settings,
        GithubReviewCommentDraft draft,
        RuntimeException ex
    ) {
        RuntimeException classified = ExternalCallErrorClassifier.github(ex);
        healthReporter.recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
        healthReporter.recordExternalFailure(classified);
        String message = healthReporter.conciseError(classified);
        GithubReviewCommentResult result = new GithubReviewCommentResult(
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
        healthReporter.markChecked(settings, message);
        return result;
    }

    private String fetchCurrentHeadSha(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        ExternalCallResilience resilience
    ) {
        return headReader.fetchHeadSha(
            settings,
            baseUrl,
            owner,
            repository,
            task.getPrNumber(),
            resilience
        );
    }

    private boolean sameCommit(String expectedCommitSha, String currentHeadSha) {
        return expectedCommitSha.equalsIgnoreCase(currentHeadSha);
    }
}
