package com.repoguard.agent.github;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
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
    private final GithubPullRequestHeadReader headReader;
    private final GithubChangedFileReader changedFileReader;
    private final GithubCommentWriter commentWriter;
    private final GithubIntegrationHealthReporter healthReporter;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GithubPullRequestClientImpl(
        GithubIntegrationProvider githubIntegrationProvider,
        ExternalCallResilience resilience,
        GithubPullRequestReader pullRequestReader,
        GithubPullRequestHeadReader headReader,
        GithubChangedFileReader changedFileReader,
        GithubCommentWriter commentWriter,
        GithubIntegrationHealthReporter healthReporter,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(
            githubIntegrationProvider,
            resilience,
            pullRequestReader,
            headReader,
            changedFileReader,
            commentWriter,
            healthReporter,
            endpointPolicy,
            true
        );
    }

    public GithubPullRequestClientImpl(
        GithubIntegrationProvider githubIntegrationProvider,
        ExternalCallResilience resilience,
        GithubPullRequestReader pullRequestReader,
        GithubPullRequestHeadReader headReader,
        GithubChangedFileReader changedFileReader,
        GithubCommentWriter commentWriter,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this(
            githubIntegrationProvider,
            resilience,
            pullRequestReader,
            headReader,
            changedFileReader,
            commentWriter,
            healthReporter,
            null,
            true
        );
    }

    private GithubPullRequestClientImpl(
        GithubIntegrationProvider githubIntegrationProvider,
        ExternalCallResilience resilience,
        GithubPullRequestReader pullRequestReader,
        GithubPullRequestHeadReader headReader,
        GithubChangedFileReader changedFileReader,
        GithubCommentWriter commentWriter,
        GithubIntegrationHealthReporter healthReporter,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.githubIntegrationProvider = Objects.requireNonNull(githubIntegrationProvider, "githubIntegrationProvider");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.pullRequestReader = Objects.requireNonNull(pullRequestReader, "pullRequestReader");
        this.headReader = Objects.requireNonNull(headReader, "headReader");
        this.changedFileReader = Objects.requireNonNull(changedFileReader, "changedFileReader");
        this.commentWriter = Objects.requireNonNull(commentWriter, "commentWriter");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.endpointPolicy = endpointPolicy;
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
    public String fetchPullRequestHeadSha(ReviewTask task) {
        GithubIntegrationSettings settings = loadGithubSettings();
        GithubRepositoryRef repositoryRef = repositoryForTask(task, settings);
        String baseUrl = baseUrl(settings);
        return healthReporter.recordReadOperation(
            settings,
            "fetch_pull_request_head",
            () -> headReader.fetchHeadSha(
                settings,
                baseUrl,
                repositoryRef.owner(),
                repositoryRef.repository(),
                task.getPrNumber(),
                resilience
            )
        );
    }

    @Override
    public GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        GithubIntegrationSettings settings = loadGithubSettings();
        GithubRepositoryRef repositoryRef = repositoryForTask(task, settings);
        String owner = repositoryRef.owner();
        String repository = repositoryRef.repository();
        String baseUrl = baseUrl(settings);
        String expectedHeadSha = requiredTaskCommitSha(task);

        return healthReporter.recordReadOperation(settings, "fetch_pull_request_diff", () -> {
            String headBeforeFetch = headReader.fetchHeadSha(
                settings,
                baseUrl,
                owner,
                repository,
                task.getPrNumber(),
                resilience
            );
            ensureExpectedHead(expectedHeadSha, headBeforeFetch);
            GithubChangedFileFetch changedFileFetch = changedFileReader.fetchChangedFiles(
                settings,
                baseUrl,
                owner,
                repository,
                task.getPrNumber(),
                resilience
            );
            String headAfterFetch = headReader.fetchHeadSha(
                settings,
                baseUrl,
                owner,
                repository,
                task.getPrNumber(),
                resilience
            );
            ensureExpectedHead(expectedHeadSha, headAfterFetch);
            return new GithubPullRequestDiff(
                owner,
                repository,
                task.getPrNumber(),
                expectedHeadSha,
                changedFileFetch.files(),
                changedFileFetch.truncation()
            );
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

    private GithubRepositoryRef repositoryForTask(ReviewTask task, GithubIntegrationSettings settings) {
        Objects.requireNonNull(task, "task");
        String owner = choose(task.getOrganization(), settings.defaultOwner());
        String repository = choose(task.getRepository(), settings.defaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }
        return new GithubRepositoryRef(owner, repository);
    }

    private String requiredTaskCommitSha(ReviewTask task) {
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalStateException("Review task commit SHA is unavailable");
        }
        return task.getCommitSha().trim();
    }

    private void ensureExpectedHead(String expectedHeadSha, String currentHeadSha) {
        if (!expectedHeadSha.equalsIgnoreCase(currentHeadSha)) {
            throw new GithubPullRequestHeadChangedException(expectedHeadSha, currentHeadSha);
        }
    }

    private String baseUrl(GithubIntegrationSettings settings) {
        String baseUrl = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_GITHUB_BASE_URL;
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, baseUrl);
        }
        return baseUrl;
    }

    private String choose(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }
}
