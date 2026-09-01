package com.repoguard.agent.github.checks;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.github.GithubIntegrationHealthReporter;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubCheckRunClient {

    private static final String DEFAULT_BASE_URL = "https://api.github.com";

    private final GithubIntegrationProvider integrationProvider;
    private final GithubCheckRunGateway gateway;
    private final ExternalCallResilience resilience;
    private final GithubIntegrationHealthReporter healthReporter;
    private final OutboundEndpointPolicy endpointPolicy;

    public GithubCheckRunClient(
        GithubIntegrationProvider integrationProvider,
        GithubCheckRunGateway gateway,
        ExternalCallResilience resilience,
        GithubIntegrationHealthReporter healthReporter,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    }

    public GithubCheckRunGateway.RemoteCheckRun findOrCreate(
        ReviewTask task,
        com.repoguard.agent.entity.GithubCheckRun record,
        GithubCheckRunGateway.Output output
    ) {
        Context context = context(task);
        try {
            GithubCheckRunGateway.RemoteCheckRun existing = resilience.github(
                "find_check_run",
                () -> gateway.find(
                    context.settings(), context.baseUrl(), context.owner(), context.repository(),
                    record.getHeadSha(), record.getName(), record.getExternalId()
                )
            );
            if (existing != null) {
                healthReporter.recordGithubApiRequest(
                    java.time.LocalDateTime.now(), "find_check_run", "success", null, null
                );
                return existing;
            }
            GithubCheckRunGateway.RemoteCheckRun created = gateway.create(
                context.settings(), context.baseUrl(), context.owner(), context.repository(),
                new GithubCheckRunGateway.CreateRequest(
                    record.getName(), record.getHeadSha(), GithubCheckRunStage.QUEUED.githubStatus(),
                    record.getExternalId(), output
                )
            );
            healthReporter.recordGithubApiRequest(
                java.time.LocalDateTime.now(), "create_check_run", "success", null, null
            );
            healthReporter.markChecked(context.settings(), null);
            return created;
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            healthReporter.recordGithubApiRequest(
                java.time.LocalDateTime.now(), "create_check_run", "failed", classified
            );
            healthReporter.recordExternalFailure(classified);
            healthReporter.markChecked(context.settings(), healthReporter.conciseError(classified));
            throw classified;
        }
    }

    public GithubCheckRunGateway.RemoteCheckRun update(
        ReviewTask task,
        com.repoguard.agent.entity.GithubCheckRun record,
        GithubCheckRunGateway.UpdateRequest request
    ) {
        if (record.getGithubCheckRunId() == null) {
            throw new IllegalStateException("GitHub Check Run id is unavailable");
        }
        Context context = context(task);
        try {
            GithubCheckRunGateway.RemoteCheckRun updated = resilience.github(
                "update_check_run",
                () -> gateway.update(
                    context.settings(), context.baseUrl(), context.owner(), context.repository(),
                    record.getGithubCheckRunId(), request
                )
            );
            healthReporter.recordGithubApiRequest(
                java.time.LocalDateTime.now(), "update_check_run", "success", null, null
            );
            healthReporter.markChecked(context.settings(), null);
            return updated;
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            healthReporter.recordGithubApiRequest(
                java.time.LocalDateTime.now(), "update_check_run", "failed", classified
            );
            healthReporter.recordExternalFailure(classified);
            healthReporter.markChecked(context.settings(), healthReporter.conciseError(classified));
            throw classified;
        }
    }

    private Context context(ReviewTask task) {
        GithubIntegrationSettings settings = integrationProvider.getSettingsForRepository(
            task.getOrganization(), task.getRepository()
        );
        String owner = task.getOrganization();
        String repository = task.getRepository();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            owner = settings.defaultOwner();
            repository = settings.defaultRepo();
        }
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured for Checks API");
        }
        String baseUrl = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_BASE_URL;
        endpointPolicy.validate(OutboundEndpointType.GITHUB, baseUrl);
        return new Context(settings, baseUrl, owner.trim(), repository.trim());
    }

    private record Context(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository
    ) {
    }
}
