package com.repoguard.agent.github;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubPullRequestClientImpl implements GithubPullRequestClient {

    private static final String DEFAULT_GITHUB_BASE_URL = "https://api.github.com";

    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ExternalCallResilience resilience;
    private final GithubPullRequestReader pullRequestReader;
    private final GithubChangedFileReader changedFileReader;
    private final GithubCommentWriter commentWriter;
    private final GithubIntegrationHealthReporter healthReporter;

    @Autowired
    public GithubPullRequestClientImpl(
        GithubIntegrationProvider githubIntegrationProvider,
        ExternalCallResilience resilience,
        GithubPullRequestReader pullRequestReader,
        GithubChangedFileReader changedFileReader,
        GithubCommentWriter commentWriter,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this.githubIntegrationProvider = Objects.requireNonNull(githubIntegrationProvider, "githubIntegrationProvider");
        this.resilience = resilience;
        this.pullRequestReader = Objects.requireNonNull(pullRequestReader, "pullRequestReader");
        this.changedFileReader = Objects.requireNonNull(changedFileReader, "changedFileReader");
        this.commentWriter = Objects.requireNonNull(commentWriter, "commentWriter");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
    }

    @Override
    public GithubRepositoryRef getConfiguredRepository() {
        GithubIntegrationSettings settings = loadGithubSettings();
        return configuredRepository(settings);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.GITHUB_OPEN_PULL_REQUESTS)
    public List<GithubPullRequestSummary> listOpenPullRequests() {
        GithubIntegrationSettings settings = loadGithubSettings();
        GithubRepositoryRef repositoryRef = configuredRepository(settings);
        String owner = repositoryRef.owner();
        String repository = repositoryRef.repository();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }
        String baseUrl = baseUrl(settings);

        return healthReporter.recordReadOperation(
            settings,
            "list_open_pull_requests",
            () -> pullRequestReader.listOpenPullRequests(
                settings,
                baseUrl,
                owner,
                repository,
                resilience
            )
        );
    }

    @Override
    public GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        GithubIntegrationSettings settings = loadGithubSettings();
        String owner = choose(task.getOrganization(), settings.defaultOwner());
        String repository = choose(task.getRepository(), settings.defaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }

        String baseUrl = baseUrl(settings);

        return healthReporter.recordReadOperation(settings, "fetch_pull_request_diff", () -> {
            List<GithubChangedFile> changedFiles = changedFileReader.fetchChangedFiles(
                settings,
                baseUrl,
                owner,
                repository,
                task.getPrNumber(),
                resilience
            );

            return new GithubPullRequestDiff(owner, repository, task.getPrNumber(), changedFiles);
        });
    }

    @Override
    public List<GithubReviewCommentResult> publishPullRequestComments(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        GithubIntegrationSettings settings = loadGithubSettings();
        String owner = choose(task.getOrganization(), settings.defaultOwner());
        String repository = choose(task.getRepository(), settings.defaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }
        return commentWriter.publishPullRequestComments(
            settings,
            baseUrl(settings),
            owner,
            repository,
            task,
            drafts,
            resilience
        );
    }

    private GithubIntegrationSettings loadGithubSettings() {
        return githubIntegrationProvider.getSettings();
    }

    private GithubRepositoryRef configuredRepository(GithubIntegrationSettings settings) {
        String owner = settings.defaultOwner();
        String repository = settings.defaultRepo();
        return new GithubRepositoryRef(
            StringUtils.hasText(owner) ? owner.trim() : null,
            StringUtils.hasText(repository) ? repository.trim() : null
        );
    }

    private String baseUrl(GithubIntegrationSettings settings) {
        return StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_GITHUB_BASE_URL;
    }

    private String choose(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }
}
