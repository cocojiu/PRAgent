package com.repoguard.agent.github;

import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubIntegrationHealthReporter {

    private final GithubIntegrationProvider githubIntegrationProvider;
    private final RepoGuardMetrics metrics;

    public GithubIntegrationHealthReporter(
        GithubIntegrationProvider githubIntegrationProvider,
        RepoGuardMetrics metrics
    ) {
        this.githubIntegrationProvider = Objects.requireNonNull(githubIntegrationProvider, "githubIntegrationProvider");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void markChecked(GithubIntegrationSettings settings, String error) {
        githubIntegrationProvider.markChecked(settings, error);
    }

    public void recordExternalFailure(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            metrics.externalCallFailed(externalCallException);
        }
    }

    public <T> T recordReadOperation(
        GithubIntegrationSettings settings,
        String operation,
        Supplier<T> supplier
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            T result = supplier.get();
            recordGithubApiRequest(startedAt, operation, "success", null, null);
            markChecked(settings, null);
            return result;
        } catch (GithubPullRequestHeadChangedException ex) {
            recordGithubApiRequest(startedAt, operation, "superseded", null, null);
            markChecked(settings, null);
            throw ex;
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            recordGithubApiRequest(startedAt, operation, "failed", classified);
            recordExternalFailure(classified);
            markChecked(settings, conciseError(classified));
            throw classified;
        }
    }

    public void recordGithubApiRequest(
        LocalDateTime startedAt,
        String operation,
        String result,
        RuntimeException ex
    ) {
        if (ex instanceof ExternalCallException externalCallException) {
            recordGithubApiRequest(
                startedAt,
                operation,
                result,
                externalCallException.getCategory(),
                externalCallException.getStatusCode() == null ? null : externalCallException.getStatusCode().toString()
            );
            return;
        }
        recordGithubApiRequest(startedAt, operation, result, null, null);
    }

    public void recordGithubApiRequest(
        LocalDateTime startedAt,
        String operation,
        String result,
        String category,
        String status
    ) {
        metrics.githubApiRequest(Duration.between(startedAt, LocalDateTime.now()), operation, result, category, status);
    }

    public String conciseError(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
