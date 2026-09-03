package com.repoguard.agent.scm;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubCommentTargetType;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.checks.GithubCheckRunGateway;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Bridges the existing GitHub implementation to the provider-neutral contract. */
@Component
public class GithubScmProviderAdapter implements ScmProvider {

    private final GithubPullRequestClient client;
    private final GithubIntegrationProvider integrationProvider;
    private final GithubCheckRunGateway checkRunGateway;

    @Autowired
    public GithubScmProviderAdapter(
        GithubPullRequestClient client,
        GithubIntegrationProvider integrationProvider,
        GithubCheckRunGateway checkRunGateway
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.checkRunGateway = Objects.requireNonNull(checkRunGateway, "checkRunGateway");
    }

    @Override
    public String providerKey() {
        return "GITHUB";
    }

    @Override
    public ScmIntegrationSettings settings() {
        GithubIntegrationSettings settings = integrationProvider.getSettings();
        return new ScmIntegrationSettings(
            providerKey(),
            settings.status(),
            settings.baseUrl(),
            settings.token(),
            settings.lastError(),
            settings.defaultOwner(),
            settings.defaultRepo(),
            settings.id()
        );
    }

    @Override
    public ScmRepositoryRef configuredRepository() {
        GithubRepositoryRef repository = client.getConfiguredRepository();
        if (repository == null || !StringUtils.hasText(repository.owner()) || !StringUtils.hasText(repository.repository())) {
            return null;
        }
        return new ScmRepositoryRef(repository.owner(), repository.repository());
    }

    @Override
    public List<ScmChangeRequestSummary> listOpenChangeRequests() {
        return client.listOpenPullRequests().stream().map(this::toSummary).toList();
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        return client.fetchPullRequestDiff(task);
    }

    @Override
    public String fetchPullRequestHeadSha(ReviewTask task) {
        return client.fetchPullRequestHeadSha(task);
    }

    @Override
    public ScmCommentResult publishComment(ReviewTask task, ScmCommentDraft draft) {
        if (task == null || draft == null || !StringUtils.hasText(draft.body())) {
            throw new IllegalArgumentException("GitHub task and comment body are required");
        }
        String targetType = draft.line() == null ? GithubCommentTargetType.PULL_REQUEST.code() : GithubCommentTargetType.LINE.code();
        List<GithubReviewCommentResult> result = client.publishPullRequestComments(task, List.of(
            new GithubReviewCommentDraft(draft.findingId(), draft.path(), draft.line(), draft.body(), targetType)
        ));
        GithubReviewCommentResult first = result == null || result.isEmpty() ? null : result.getFirst();
        return first == null
            ? new ScmCommentResult(providerKey(), draft.findingId(), false, "FAILED", "GitHub returned no comment result", null, null)
            : new ScmCommentResult(providerKey(), first.findingId(), Boolean.TRUE.equals(first.success()), first.status(),
                first.message(), first.url(), first.commentId());
    }

    @Override
    public ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request) {
        if (task == null || request == null || !StringUtils.hasText(request.state())) {
            throw new IllegalArgumentException("GitHub task and status state are required");
        }
        GithubIntegrationSettings settings = integrationProvider.getSettingsForRepository(
            task.getOrganization(), task.getRepository()
        );
        GithubRepositoryRef repository = new GithubRepositoryRef(task.getOrganization(), task.getRepository());
        String baseUrl = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl() : "https://api.github.com";
        String state = request.state().trim().toLowerCase(Locale.ROOT);
        String status = switch (state) {
            case "pending", "running", "in_progress" -> "in_progress";
            default -> "completed";
        };
        String conclusion = switch (state) {
            case "success", "passed" -> "success";
            case "failure", "failed", "action_required" -> "failure";
            case "cancelled", "canceled" -> "cancelled";
            default -> null;
        };
        GithubCheckRunGateway.Output output = new GithubCheckRunGateway.Output(
            request.name(), request.description(), null, List.of()
        );
        GithubCheckRunGateway.RemoteCheckRun remote = checkRunGateway.create(
            settings,
            baseUrl,
            repository.owner(),
            repository.repository(),
            new GithubCheckRunGateway.CreateRequest(
                request.name(), StringUtils.hasText(task.getCommitSha()) ? task.getCommitSha() : client.fetchPullRequestHeadSha(task), status,
                "repoguard-scm-" + (task.getId() == null ? task.getPrNumber() : task.getId()), output
            )
        );
        if (conclusion != null && remote != null && remote.id() != null) {
            checkRunGateway.update(settings, baseUrl, repository.owner(), repository.repository(), remote.id(),
                new GithubCheckRunGateway.UpdateRequest(status, conclusion, null, null, output));
        }
        return new ScmStatusResult(providerKey(), true, state, "GitHub Check Run updated", null);
    }

    private ScmChangeRequestSummary toSummary(GithubPullRequestSummary item) {
        return new ScmChangeRequestSummary(
            providerKey(), item.owner(), item.repository(), item.number(), item.title(), item.branch(),
            item.commit(), item.author(), item.url(), item.updatedAt()
        );
    }
}
