package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Facade for GitHub comment publication. Specialized collaborators own review batching,
 * per-comment fallback, superseded summaries, and HTTP protocol details.
 */
@Component
public class GithubCommentWriter {

    private final GithubIntegrationHealthReporter healthReporter;
    private final GithubPullRequestHeadReader headReader;
    private final OutboundEndpointPolicy endpointPolicy;
    private final GithubCommentPublicationGateway publicationGateway;
    private final GithubReviewBatchPublisher reviewBatchPublisher;
    private final GithubLineCommentFallbackPublisher lineCommentFallbackPublisher;
    private final GithubSupersededSummaryPublisher supersededSummaryPublisher;

    @Autowired
    public GithubCommentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter,
        ExternalHttpJsonResponseReader jsonResponseReader,
        GithubPullRequestHeadReader headReader,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.headReader = Objects.requireNonNull(headReader, "headReader");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.publicationGateway = new GithubCommentPublicationGateway(restClientBuilder, jsonResponseReader);
        this.supersededSummaryPublisher = new GithubSupersededSummaryPublisher(
            publicationGateway,
            healthReporter
        );
        this.reviewBatchPublisher = new GithubReviewBatchPublisher(publicationGateway, healthReporter);
        this.lineCommentFallbackPublisher = new GithubLineCommentFallbackPublisher(
            publicationGateway,
            headReader,
            supersededSummaryPublisher,
            healthReporter
        );
    }

    GithubCommentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter,
        ExternalHttpJsonResponseReader jsonResponseReader
    ) {
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.publicationGateway = new GithubCommentPublicationGateway(restClientBuilder, jsonResponseReader);
        this.headReader = new GithubPullRequestHeadReader(restClientBuilder, jsonResponseReader);
        this.supersededSummaryPublisher = new GithubSupersededSummaryPublisher(
            publicationGateway,
            healthReporter
        );
        this.reviewBatchPublisher = new GithubReviewBatchPublisher(publicationGateway, healthReporter);
        this.lineCommentFallbackPublisher = new GithubLineCommentFallbackPublisher(
            publicationGateway,
            headReader,
            supersededSummaryPublisher,
            healthReporter
        );
        this.endpointPolicy = null;
    }

    public List<GithubReviewCommentResult> publishPullRequestComments(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        List<GithubReviewCommentDraft> drafts,
        ExternalCallResilience resilience
    ) {
        ExternalCallResilience effectiveResilience = Objects.requireNonNull(resilience, "resilience");
        if (!StringUtils.hasText(settings.token())) {
            throw new IllegalStateException("GitHub token is not configured");
        }
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, baseUrl);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        String reviewUrl = pullRequestReviewUrl(baseUrl, owner, repository, task);
        String lineCommentUrl = lineCommentUrl(baseUrl, owner, repository, task);
        String prCommentUrl = pullRequestCommentUrl(baseUrl, owner, repository, task);

        GithubReviewCommentResult[] results = new GithubReviewCommentResult[drafts.size()];
        List<Integer> lineDraftIndexes = new ArrayList<>();
        int failedCount = publishPullRequestDrafts(
            settings,
            drafts,
            results,
            lineDraftIndexes,
            prCommentUrl,
            effectiveResilience,
            startedAt
        );
        failedCount += publishLineDrafts(
            settings,
            baseUrl,
            owner,
            repository,
            task,
            drafts,
            lineDraftIndexes,
            results,
            reviewUrl,
            lineCommentUrl,
            prCommentUrl,
            effectiveResilience,
            startedAt
        );

        healthReporter.recordGithubApiRequest(
            startedAt,
            "publish_pull_request_comments",
            failedCount > 0 ? "partial" : "success",
            null,
            null
        );
        return List.of(results);
    }

    private int publishPullRequestDrafts(
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> drafts,
        GithubReviewCommentResult[] results,
        List<Integer> lineDraftIndexes,
        String prCommentUrl,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        int failedCount = 0;
        for (int index = 0; index < drafts.size(); index++) {
            GithubReviewCommentDraft draft = drafts.get(index);
            if (!GithubCommentTargetType.from(draft.targetType()).isPullRequest()) {
                lineDraftIndexes.add(index);
                continue;
            }
            try {
                GithubCommentResponse response = publicationGateway.publishPullRequestComment(
                    prCommentUrl,
                    draft.body(),
                    settings,
                    resilience
                );
                results[index] = publishedResult(draft, response);
                healthReporter.markChecked(settings, null);
            } catch (RuntimeException ex) {
                failedCount++;
                results[index] = failedResult(startedAt, settings, draft, ex);
            }
        }
        return failedCount;
    }

    private int publishLineDrafts(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        List<GithubReviewCommentDraft> drafts,
        List<Integer> lineDraftIndexes,
        GithubReviewCommentResult[] results,
        String reviewUrl,
        String lineCommentUrl,
        String prCommentUrl,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        if (lineDraftIndexes.isEmpty()) {
            return 0;
        }
        List<GithubReviewCommentDraft> lineDrafts = lineDraftIndexes.stream().map(drafts::get).toList();
        String expectedCommitSha;
        String currentHeadSha;
        try {
            expectedCommitSha = requiredTaskCommitSha(task);
            currentHeadSha = fetchCurrentHeadSha(settings, baseUrl, owner, repository, task, resilience);
        } catch (RuntimeException ex) {
            return applyLineResults(
                lineDraftIndexes,
                results,
                failedBatch(startedAt, settings, lineDrafts, ex)
            );
        }
        if (!sameCommit(expectedCommitSha, currentHeadSha)) {
            return applyLineResults(
                lineDraftIndexes,
                results,
                supersededSummaryPublisher.publish(
                    settings,
                    lineDrafts,
                    prCommentUrl,
                    expectedCommitSha,
                    currentHeadSha,
                    resilience,
                    startedAt
                )
            );
        }

        try {
            return applyLineResults(
                lineDraftIndexes,
                results,
                new GithubCommentBatchResult(
                    reviewBatchPublisher.publish(
                        reviewUrl,
                        lineDrafts,
                        expectedCommitSha,
                        settings,
                        resilience
                    ),
                    0
                )
            );
        } catch (RuntimeException ex) {
            if (!reviewBatchPublisher.isValidationFailure(ex)) {
                return applyLineResults(
                    lineDraftIndexes,
                    results,
                    failedBatch(startedAt, settings, lineDrafts, ex)
                );
            }
        }

        try {
            currentHeadSha = fetchCurrentHeadSha(settings, baseUrl, owner, repository, task, resilience);
        } catch (RuntimeException ex) {
            return applyLineResults(
                lineDraftIndexes,
                results,
                failedBatch(startedAt, settings, lineDrafts, ex)
            );
        }
        if (!sameCommit(expectedCommitSha, currentHeadSha)) {
            return applyLineResults(
                lineDraftIndexes,
                results,
                supersededSummaryPublisher.publish(
                    settings,
                    lineDrafts,
                    prCommentUrl,
                    expectedCommitSha,
                    currentHeadSha,
                    resilience,
                    startedAt
                )
            );
        }
        return applyLineResults(
            lineDraftIndexes,
            results,
            lineCommentFallbackPublisher.publish(
                settings,
                baseUrl,
                owner,
                repository,
                task,
                lineDrafts,
                lineCommentUrl,
                prCommentUrl,
                expectedCommitSha,
                resilience,
                startedAt
            )
        );
    }

    private int applyLineResults(
        List<Integer> lineDraftIndexes,
        GithubReviewCommentResult[] results,
        GithubCommentBatchResult batchResult
    ) {
        for (int position = 0; position < lineDraftIndexes.size(); position++) {
            results[lineDraftIndexes.get(position)] = batchResult.results().get(position);
        }
        return batchResult.failedCount();
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
        List<GithubReviewCommentResult> failedResults = drafts.stream()
            .map(draft -> failedResult(draft, message))
            .toList();
        healthReporter.markChecked(settings, message);
        return new GithubCommentBatchResult(failedResults, drafts.size());
    }

    private GithubReviewCommentResult publishedResult(
        GithubReviewCommentDraft draft,
        GithubCommentResponse response
    ) {
        return new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            GithubCommentTargetType.PULL_REQUEST.code(),
            true,
            GithubCommentPublicationStatus.PUBLISHED.code(),
            "GitHub comment published",
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
        GithubReviewCommentResult result = failedResult(draft, message);
        healthReporter.markChecked(settings, message);
        return result;
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

    private String requiredTaskCommitSha(ReviewTask task) {
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalStateException("Review task commit SHA is unavailable");
        }
        return task.getCommitSha().trim();
    }

    private boolean sameCommit(String expectedCommitSha, String currentHeadSha) {
        return expectedCommitSha.equalsIgnoreCase(currentHeadSha);
    }

    private String pullRequestReviewUrl(String baseUrl, String owner, String repository, ReviewTask task) {
        return UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
            .build(owner, repository, task.getPrNumber())
            .toString();
    }

    private String lineCommentUrl(String baseUrl, String owner, String repository, ReviewTask task) {
        return UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
            .build(owner, repository, task.getPrNumber())
            .toString();
    }

    private String pullRequestCommentUrl(String baseUrl, String owner, String repository, ReviewTask task) {
        return UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/issues/{pullNumber}/comments")
            .build(owner, repository, task.getPrNumber())
            .toString();
    }
}
