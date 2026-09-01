package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.github.GithubIntegrationHealthReporter;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GithubCheckRunClientTest {

    private final GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
    private final GithubCheckRunGateway gateway = mock(GithubCheckRunGateway.class);
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final GithubIntegrationHealthReporter healthReporter = mock(GithubIntegrationHealthReporter.class);
    private final OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
    private final GithubCheckRunClient client = new GithubCheckRunClient(
        integrationProvider, gateway, resilience, healthReporter, endpointPolicy
    );

    @Test
    void findsExistingRunWithoutCreatingAnother() {
        ReviewTask task = task();
        GithubCheckRun record = record();
        GithubIntegrationSettings settings = settings();
        GithubCheckRunGateway.RemoteCheckRun existing = new GithubCheckRunGateway.RemoteCheckRun(
            99L, record.getExternalId(), "queued", null
        );
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(resilience.github(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(gateway.find(settings, "https://api.github.com", "octo", "repo", record.getHeadSha(),
            record.getName(), record.getExternalId())).thenReturn(existing);

        GithubCheckRunGateway.RemoteCheckRun result = client.findOrCreate(task, record, output());

        assertThat(result).isSameAs(existing);
        verify(gateway).find(settings, "https://api.github.com", "octo", "repo", record.getHeadSha(),
            record.getName(), record.getExternalId());
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).create(any(), any(), any(), any(), any());
        verify(endpointPolicy).validate(com.repoguard.agent.external.OutboundEndpointType.GITHUB,
            "https://api.github.com");
    }

    @Test
    void createsAndUpdatesRunWithConfiguredRepositorySettings() {
        ReviewTask task = task();
        GithubCheckRun record = record();
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://github.example.test", "token", null, null, null, 3L
        );
        GithubCheckRunGateway.RemoteCheckRun created = new GithubCheckRunGateway.RemoteCheckRun(
            100L, record.getExternalId(), "queued", null
        );
        GithubCheckRunGateway.RemoteCheckRun updated = new GithubCheckRunGateway.RemoteCheckRun(
            100L, record.getExternalId(), "completed", "success"
        );
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(resilience.github(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(gateway.find(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(null);
        when(gateway.create(eq(settings), eq("https://github.example.test"), eq("octo"), eq("repo"), any()))
            .thenReturn(created);
        when(gateway.update(eq(settings), eq("https://github.example.test"), eq("octo"), eq("repo"), eq(100L), any()))
            .thenReturn(updated);

        assertThat(client.findOrCreate(task, record, output())).isSameAs(created);
        assertThat(client.update(task, record, new GithubCheckRunGateway.UpdateRequest(
            "completed", "success", null, null, output()
        ))).isSameAs(updated);
        verify(healthReporter, org.mockito.Mockito.atLeastOnce()).markChecked(eq(settings), eq(null));
    }

    @Test
    void rejectsMissingOwnerBeforeMakingExternalCall() {
        ReviewTask task = task();
        task.setOrganization(null);
        task.setRepository(null);
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "token", null, null, null, 3L
        );
        when(integrationProvider.getSettingsForRepository(null, null)).thenReturn(settings);
        when(resilience.github(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

        assertThatThrownBy(() -> client.findOrCreate(task, record(), output()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner or repository");
    }

    @Test
    void usesConfiguredDefaultOwnerAndRepositoryWhenTaskOmitsThem() {
        ReviewTask task = task();
        task.setOrganization(null);
        task.setRepository(null);
        GithubCheckRun record = record();
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "token", null, "default-owner", "default-repo", 3L
        );
        GithubCheckRunGateway.RemoteCheckRun existing = new GithubCheckRunGateway.RemoteCheckRun(
            101L, record.getExternalId(), "queued", null
        );
        when(integrationProvider.getSettingsForRepository(null, null)).thenReturn(settings);
        when(resilience.github(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(gateway.find(settings, "https://api.github.com", "default-owner", "default-repo",
            record.getHeadSha(), record.getName(), record.getExternalId())).thenReturn(existing);

        assertThat(client.findOrCreate(task, record, output())).isSameAs(existing);
        verify(gateway).find(settings, "https://api.github.com", "default-owner", "default-repo",
            record.getHeadSha(), record.getName(), record.getExternalId());
    }

    @Test
    void recordsAndClassifiesFindFailure() {
        ReviewTask task = task();
        GithubCheckRun record = record();
        GithubIntegrationSettings settings = settings();
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(healthReporter.conciseError(any(RuntimeException.class))).thenReturn("GitHub unavailable");
        when(resilience.github(anyString(), any())).thenAnswer(inv -> {
            throw new IllegalStateException("GitHub unavailable");
        });

        assertThatThrownBy(() -> client.findOrCreate(task, record, output()))
            .isInstanceOf(com.repoguard.agent.external.ExternalCallException.class)
            .hasMessageContaining("GitHub unavailable");
        verify(healthReporter).recordExternalFailure(any(RuntimeException.class));
        verify(healthReporter).markChecked(eq(settings), anyString());
    }

    @Test
    void recordsAndClassifiesUpdateFailureAndRejectsMissingRemoteId() {
        ReviewTask task = task();
        GithubCheckRun record = record();
        GithubIntegrationSettings settings = settings();
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(healthReporter.conciseError(any(RuntimeException.class))).thenReturn("Checks update unavailable");
        when(resilience.github(anyString(), any())).thenAnswer(inv -> {
            throw new IllegalStateException("Checks update unavailable");
        });

        assertThatThrownBy(() -> client.update(task, record, new GithubCheckRunGateway.UpdateRequest(
            "completed", "failure", null, null, output()
        ))).isInstanceOf(com.repoguard.agent.external.ExternalCallException.class)
            .hasMessageContaining("Checks update unavailable");
        verify(healthReporter).recordExternalFailure(any(RuntimeException.class));
        verify(healthReporter).markChecked(eq(settings), anyString());

        GithubCheckRun missingId = record();
        missingId.setGithubCheckRunId(null);
        assertThatThrownBy(() -> client.update(task, missingId, new GithubCheckRunGateway.UpdateRequest(
            "completed", "success", null, null, output()
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("id is unavailable");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setOrganization("octo");
        task.setRepository("repo");
        task.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        return task;
    }

    private GithubCheckRun record() {
        GithubCheckRun record = new GithubCheckRun();
        record.setId(8L);
        record.setTaskId(7L);
        record.setRunSequence(1);
        record.setGithubCheckRunId(100L);
        record.setName("RepoGuard PR Review");
        record.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        record.setExternalId("repoguard-task:7:run:1");
        return record;
    }

    private GithubIntegrationSettings settings() {
        return new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "token", null, "octo", "repo", 3L
        );
    }

    private GithubCheckRunGateway.Output output() {
        return new GithubCheckRunGateway.Output("title", "summary", null, java.util.List.of());
    }
}
