package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.GithubChecksDiagnosticDto;
import com.repoguard.agent.dto.GithubChecksPolicyRequest;
import com.repoguard.agent.dto.GithubChecksPreviewRequest;
import com.repoguard.agent.dto.GithubChecksSetupStatusDto;
import com.repoguard.agent.entity.GithubCheckRunPolicy;
import com.repoguard.agent.github.GithubAppProperties;
import com.repoguard.agent.github.GithubIntegrationHealthReporter;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.github.webhook.GithubWebhookDeliveryTracker;
import com.repoguard.agent.github.webhook.GithubWebhookProperties;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GithubChecksSetupServiceTest {

    private final GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
    private final GithubIntegrationHealthReporter healthReporter = mock(GithubIntegrationHealthReporter.class);
    private final GithubCheckRunGateway gateway = mock(GithubCheckRunGateway.class);
    private final GithubCheckRunPolicyService policyService = mock(GithubCheckRunPolicyService.class);
    private final TenantRepositoryResolver tenantRepositoryResolver = mock(TenantRepositoryResolver.class);
    private final GithubWebhookDeliveryTracker deliveryTracker = mock(GithubWebhookDeliveryTracker.class);

    @Test
    void disabledAppKeepsChecksFailClosedButReportsPatFallback() {
        GithubAppProperties appProperties = new GithubAppProperties();
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        GithubIntegrationSettings patSettings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "ghp_test", null, "octo", "repo", 1L
        );
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", null));
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(patSettings);
        when(policyService.find("octo", "repo")).thenReturn(null);

        GithubChecksSetupStatusDto status = service(
            appProperties, checkProperties, webhookProperties
        ).status("octo", "repo");

        assertThat(status.appEnabled()).isFalse();
        assertThat(status.effectiveCheckRunEnabled()).isFalse();
        assertThat(status.ready()).isFalse();
        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::code)
            .contains("github_app_disabled", "personal_pat_available");
        verify(gateway, never()).inspectInstallation(any(), anyString(), anyString(), anyString());
    }

    @Test
    void previewDoesNotCreateCheckRunWhenPrerequisitesFail() {
        GithubAppProperties appProperties = new GithubAppProperties();
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", null));
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(GithubIntegrationSettings.empty());
        when(policyService.find("octo", "repo")).thenReturn(null);

        GithubChecksSetupStatusDto status = service(
            appProperties, checkProperties, webhookProperties
        ).preview(new GithubChecksPreviewRequest("octo", "repo", 7));

        assertThat(status.preview().attempted()).isTrue();
        assertThat(status.preview().created()).isFalse();
        assertThat(status.preview().status()).isEqualTo("BLOCKED");
        verify(gateway, never()).createPreview(any(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    @Test
    void readySetupCreatesNeutralPreviewAfterStableHeadRead() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        appProperties.setAppId(123L);
        appProperties.setPrivateKey("private-key-is-not-returned");
        appProperties.setAllowedInstallationIds(java.util.Set.of(77L));
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        checkProperties.setEnabled(true);
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setSecret("webhook-secret");
        webhookProperties.setAllowedRepositories(java.util.List.of("octo/repo"));
        webhookProperties.setAllowedHeadBranches(java.util.List.of("main"));
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "installation-token", null, "octo", "repo", 1L
        );
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", 77L));
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(healthReporter.recordReadOperation(any(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(2);
            return operation.get();
        });
        when(gateway.inspectInstallation(settings, settings.baseUrl(), "octo", "repo"))
            .thenReturn(new GithubCheckRunGateway.InstallationInspection(
                true, Map.of("metadata", true, "contents", true, "pull_requests", true, "checks", true)
            ));
        when(gateway.pullRequestHead(settings, settings.baseUrl(), "octo", "repo", 7))
            .thenReturn(new GithubCheckRunGateway.PullRequestHead("sha-7", "main", "2026-09-03T00:00:00Z"));
        when(gateway.createPreview(eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"),
            eq(checkProperties.getName()), eq("sha-7"), anyString(), any()))
            .thenReturn(new GithubCheckRunGateway.RemoteCheckRun(99L, "preview", "completed", null));
        when(policyService.find("octo", "repo")).thenReturn(null);

        GithubChecksSetupStatusDto status = service(
            appProperties, checkProperties, webhookProperties
        ).preview(new GithubChecksPreviewRequest("octo", "repo", 7));

        assertThat(status.ready()).isTrue();
        assertThat(status.preview().created()).isTrue();
        assertThat(status.preview().conclusion()).isEqualTo("neutral");
        assertThat(status.preview().remoteCheckRunId()).isEqualTo(99L);
        verify(gateway).createPreview(eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"),
            eq(checkProperties.getName()), eq("sha-7"), anyString(), any());
    }

    @Test
    void changedPullRequestHeadSupersedesPreviewWithoutCreatingCheck() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        appProperties.setAppId(123L);
        appProperties.setPrivateKey("private-key");
        appProperties.setAllowedInstallationIds(Set.of(77L));
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        checkProperties.setEnabled(true);
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setSecret("webhook-secret");
        webhookProperties.setAllowedRepositories(List.of("octo/repo"));
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "installation-token", null, "octo", "repo", 1L
        );
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", 77L));
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(policyService.find("octo", "repo")).thenReturn(null);
        when(healthReporter.recordReadOperation(any(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(2);
            return operation.get();
        });
        when(gateway.inspectInstallation(settings, settings.baseUrl(), "octo", "repo"))
            .thenReturn(new GithubCheckRunGateway.InstallationInspection(
                true, Map.of("metadata", true, "contents", true, "pull_requests", true, "checks", true)
            ));
        when(gateway.pullRequestHead(settings, settings.baseUrl(), "octo", "repo", 7))
            .thenReturn(
                new GithubCheckRunGateway.PullRequestHead("sha-old", "main", "2026-09-03T00:00:00Z"),
                new GithubCheckRunGateway.PullRequestHead("sha-new", "main", "2026-09-03T00:01:00Z")
            );

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties)
            .preview(new GithubChecksPreviewRequest("octo", "repo", 7));

        assertThat(status.preview().status()).isEqualTo("SUPERSEDED");
        assertThat(status.preview().created()).isFalse();
        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::code)
            .contains("head_sha_changed");
        verify(gateway, never()).createPreview(any(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    @Test
    void setPolicyRequiresConfirmationAndReadinessBeforeEnabling() {
        GithubAppProperties appProperties = new GithubAppProperties();
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", null));
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(GithubIntegrationSettings.empty());
        when(policyService.find("octo", "repo")).thenReturn(null);
        GithubChecksSetupService setup = service(appProperties, checkProperties, webhookProperties);

        assertThatThrownBy(() -> setup.setPolicy(
            new GithubChecksPolicyRequest("octo", "repo", true, 0L, false), "admin"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("确认");
        assertThatThrownBy(() -> setup.setPolicy(
            new GithubChecksPolicyRequest("octo", "repo", true, 0L, true), "admin"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("前置自检");
        verify(policyService, never()).setEnabled(anyString(), anyString(), eq(true), eq(0L), anyString());
    }

    @Test
    void disablingPolicyRemainsAvailableWhenChecksAreNotReady() {
        GithubAppProperties appProperties = new GithubAppProperties();
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        GithubCheckRunPolicy policy = new GithubCheckRunPolicy();
        policy.setEnabled(false);
        policy.setPolicyVersion(2L);
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", null));
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(GithubIntegrationSettings.empty());
        when(policyService.setEnabled("octo", "repo", false, 2L, "admin")).thenReturn(policy);
        when(policyService.find("octo", "repo")).thenReturn(policy);

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties).setPolicy(
            new GithubChecksPolicyRequest("octo", "repo", false, 2L, true), "admin"
        );

        assertThat(status.repositoryCheckRunEnabled()).isFalse();
        assertThat(status.policyVersion()).isEqualTo(2L);
        verify(policyService).setEnabled("octo", "repo", false, 2L, "admin");
    }

    @Test
    void reportsIncompleteAppMissingInstallationAndUnsafeWebhook() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setEnabled(false);
        webhookProperties.setRequireSignature(false);
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", null));
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "https://api.github.com", "ghp_pat", null, "octo", "repo", 1L
            ));
        when(policyService.find("octo", "repo")).thenReturn(null);

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties)
            .status("octo", "repo");

        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::code).contains(
            "github_app_not_configured", "installation_missing", "webhook_disabled",
            "webhook_signature_disabled", "webhook_repository_allowlist_missing"
        );
    }

    @Test
    void classifiesRetryableApiFailureAndRejectedDeliveryWithoutLeakingPayload() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        appProperties.setAppId(123L);
        appProperties.setPrivateKey("private-key");
        appProperties.setAllowedInstallationIds(Set.of(77L));
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setSecret("secret");
        webhookProperties.setAllowedRepositories(List.of("octo/repo"));
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "installation-token", null, "octo", "repo", 1L
        );
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", 77L));
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(policyService.find("octo", "repo")).thenReturn(null);
        when(deliveryTracker.latestFor("octo", "repo")).thenReturn(new GithubWebhookDeliveryTracker.Delivery(
            "deli…3456", "check_run", "octo/repo", "rejected_bad_signature",
            Instant.parse("2026-09-03T00:00:00Z")
        ));
        when(healthReporter.recordReadOperation(any(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(2);
            return operation.get();
        });
        when(gateway.inspectInstallation(settings, settings.baseUrl(), "octo", "repo"))
            .thenThrow(new com.repoguard.agent.external.ExternalCallException(
                "GitHub", "github_rate_limited", true, 429, "retry-after=30", null
            ));

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties)
            .status("octo", "repo");

        assertThat(status.ready()).isFalse();
        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::code).contains(
            "github_rate_limited", "webhook_delivery_rejected"
        );
        assertThat(status.webhook().lastDeliveryId()).isEqualTo("deli…3456");
    }

    @Test
    void enablingPolicyAfterReadySelfCheckReturnsEnabledStatus() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        appProperties.setAppId(123L);
        appProperties.setPrivateKey("private-key");
        appProperties.setAllowedInstallationIds(Set.of(77L));
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        checkProperties.setEnabled(true);
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setSecret("secret");
        webhookProperties.setAllowedRepositories(List.of("octo/repo"));
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "installation-token", null, "octo", "repo", 1L
        );
        GithubCheckRunPolicy policy = new GithubCheckRunPolicy();
        policy.setEnabled(true);
        policy.setPolicyVersion(1L);
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", 77L));
        when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
        when(policyService.find("octo", "repo")).thenReturn(policy);
        when(policyService.setEnabled("octo", "repo", true, 0L, "admin")).thenReturn(policy);
        when(healthReporter.recordReadOperation(any(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(2);
            return operation.get();
        });
        when(gateway.inspectInstallation(settings, settings.baseUrl(), "octo", "repo"))
            .thenReturn(new GithubCheckRunGateway.InstallationInspection(
                true, Map.of("metadata", true, "contents", true, "pull_requests", true, "checks", true)
            ));

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties).setPolicy(
            new GithubChecksPolicyRequest("octo", "repo", true, 0L, true), "admin"
        );

        assertThat(status.repositoryCheckRunEnabled()).isTrue();
        assertThat(status.effectiveCheckRunEnabled()).isTrue();
        verify(policyService).setEnabled("octo", "repo", true, 0L, "admin");
    }

    @Test
    void genericProbeFailureAndMissingWebhookSecretAreReportedSafely() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setEnabled(true);
        appProperties.setAppId(123L);
        appProperties.setPrivateKey("private-key");
        appProperties.setAllowedInstallationIds(Set.of(77L));
        GithubCheckRunProperties checkProperties = new GithubCheckRunProperties();
        GithubWebhookProperties webhookProperties = new GithubWebhookProperties();
        webhookProperties.setAllowedRepositories(List.of("octo/repo"));
        when(tenantRepositoryResolver.resolve(eq("octo"), eq("repo"), eq(null)))
            .thenReturn(new TenantRepositoryBinding(1L, "default", "octo", "repo", 77L));
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenThrow(new IllegalStateException("provider unavailable"));
        when(policyService.find("octo", "repo")).thenReturn(null);

        GithubChecksSetupStatusDto status = service(appProperties, checkProperties, webhookProperties)
            .status("octo", "repo");

        assertThat(status.ready()).isFalse();
        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::code).contains(
            "github_probe_failed", "webhook_secret_missing"
        );
        assertThat(status.diagnostics()).extracting(GithubChecksDiagnosticDto::message)
            .noneMatch(message -> message.contains("private-key"));
    }

    private GithubChecksSetupService service(
        GithubAppProperties appProperties,
        GithubCheckRunProperties checkProperties,
        GithubWebhookProperties webhookProperties
    ) {
        return new GithubChecksSetupService(
            appProperties,
            checkProperties,
            webhookProperties,
            integrationProvider,
            healthReporter,
            gateway,
            policyService,
            tenantRepositoryResolver,
            deliveryTracker
        );
    }
}
