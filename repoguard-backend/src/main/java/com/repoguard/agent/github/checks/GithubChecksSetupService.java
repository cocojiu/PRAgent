package com.repoguard.agent.github.checks;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubChecksDiagnosticDto;
import com.repoguard.agent.dto.GithubChecksPolicyRequest;
import com.repoguard.agent.dto.GithubChecksPreviewDto;
import com.repoguard.agent.dto.GithubChecksPreviewRequest;
import com.repoguard.agent.dto.GithubChecksSetupStatusDto;
import com.repoguard.agent.entity.GithubCheckRunPolicy;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubAppProperties;
import com.repoguard.agent.github.GithubIntegrationHealthReporter;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubChecksSetupService {

    private final GithubAppProperties appProperties;
    private final GithubCheckRunProperties checkRunProperties;
    private final GithubIntegrationProvider integrationProvider;
    private final GithubIntegrationHealthReporter healthReporter;
    private final GithubCheckRunGateway gateway;
    private final GithubCheckRunPolicyService policyService;
    private final TenantRepositoryResolver tenantRepositoryResolver;
    private final GithubChecksSetupDiagnostics setupDiagnostics;

    @Autowired
    public GithubChecksSetupService(
        GithubAppProperties appProperties,
        GithubCheckRunProperties checkRunProperties,
        GithubIntegrationProvider integrationProvider,
        GithubIntegrationHealthReporter healthReporter,
        GithubCheckRunGateway gateway,
        GithubCheckRunPolicyService policyService,
        TenantRepositoryResolver tenantRepositoryResolver,
        GithubChecksSetupDiagnostics setupDiagnostics
    ) {
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.checkRunProperties = Objects.requireNonNull(checkRunProperties, "checkRunProperties");
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.policyService = Objects.requireNonNull(policyService, "policyService");
        this.tenantRepositoryResolver = Objects.requireNonNull(tenantRepositoryResolver, "tenantRepositoryResolver");
        this.setupDiagnostics = Objects.requireNonNull(setupDiagnostics, "setupDiagnostics");
    }

    public GithubChecksSetupService(
        GithubAppProperties appProperties,
        GithubCheckRunProperties checkRunProperties,
        com.repoguard.agent.github.webhook.GithubWebhookProperties webhookProperties,
        GithubIntegrationProvider integrationProvider,
        GithubIntegrationHealthReporter healthReporter,
        GithubCheckRunGateway gateway,
        GithubCheckRunPolicyService policyService,
        TenantRepositoryResolver tenantRepositoryResolver,
        com.repoguard.agent.github.webhook.GithubWebhookDeliveryTracker deliveryTracker
    ) {
        this(
            appProperties,
            checkRunProperties,
            integrationProvider,
            healthReporter,
            gateway,
            policyService,
            tenantRepositoryResolver,
            new GithubChecksSetupDiagnostics(checkRunProperties, webhookProperties, deliveryTracker)
        );
    }

    public GithubChecksSetupStatusDto status(String organization, String repository) {
        String owner = normalize(organization, "organization");
        String repo = normalize(repository, "repository");
        List<GithubChecksDiagnosticDto> diagnostics = new ArrayList<>();
        Probe probe = probe(owner, repo, diagnostics);
        return assemble(owner, repo, probe, diagnostics, GithubChecksPreviewDto.notAttempted());
    }

    public GithubChecksSetupStatusDto preview(GithubChecksPreviewRequest request) {
        Objects.requireNonNull(request, "request");
        String owner = normalize(request.organization(), "organization");
        String repo = normalize(request.repository(), "repository");
        List<GithubChecksDiagnosticDto> diagnostics = new ArrayList<>();
        Probe probe = probe(owner, repo, diagnostics);
        if (!probe.ready()) {
            diagnostics.add(diagnostic(
                "preview_prerequisites", "Preview Check Run", "warning",
                "前置自检未通过，未创建任何 Check Run", true
            ));
            return assemble(owner, repo, probe, diagnostics, failedPreview(
                null, "BLOCKED", "前置自检未通过；不会创建阻断 Check"
            ));
        }

        GithubChecksPreviewDto preview;
        try {
            GithubCheckRunGateway.PullRequestHead first = readHead(probe.settings(), probe.baseUrl(), owner, repo,
                request.pullRequestNumber());
            GithubCheckRunGateway.PullRequestHead second = readHead(probe.settings(), probe.baseUrl(), owner, repo,
                request.pullRequestNumber());
            if (!first.sha().equals(second.sha())) {
                diagnostics.add(diagnostic(
                    "head_sha_changed", "测试 PR head SHA", "danger",
                    "两次读取到的 head SHA 不一致，已放弃预览，请重新运行", true
                ));
                preview = failedPreview(first.sha(), "SUPERSEDED", "测试 PR 在预览期间发生 head SHA 变化");
            } else {
                String externalId = "repoguard-setup-preview:" + UUID.randomUUID();
                GithubCheckRunGateway.Output output = new GithubCheckRunGateway.Output(
                    "RepoGuard Checks 设置预览",
                    "这是一个 neutral、非阻断的设置预览。确认启用前不会改变合并门禁。",
                    "请在 GitHub 检查列表中确认 RepoGuard 能写入目标提交。",
                    List.of()
                );
                GithubCheckRunGateway.RemoteCheckRun remote = healthReporter.recordReadOperation(
                    probe.settings(),
                    "checks.setup.preview.create",
                    () -> gateway.createPreview(
                        probe.settings(), probe.baseUrl(), owner, repo, checkRunProperties.getName().trim(),
                        first.sha(), externalId, output
                    )
                );
                preview = new GithubChecksPreviewDto(
                    true, true, first.sha(), externalId, remote.id(), "COMPLETED", 1,
                    "COMPLETED", 1, 0, 0, false, "completed",
                    StringUtils.hasText(remote.conclusion()) ? remote.conclusion() : "neutral",
                    "已创建 neutral 预览 Check Run；不会阻断合并"
                );
                diagnostics.add(diagnostic(
                    "preview_created", "Check Run 预览", "success",
                    "测试 PR 的 neutral Check Run 已创建", false
                ));
            }
        } catch (RuntimeException exception) {
            preview = failedPreview(null, "FAILED", conciseError(exception));
            diagnostics.add(externalDiagnostic(exception));
        }
        return assemble(owner, repo, probe, diagnostics, preview);
    }

    public GithubChecksSetupStatusDto setPolicy(GithubChecksPolicyRequest request, String operator) {
        Objects.requireNonNull(request, "request");
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "启停 Check Run 必须明确确认");
        }
        String owner = normalize(request.organization(), "organization");
        String repo = normalize(request.repository(), "repository");
        tenantRepositoryResolver.resolve(owner, repo, null);
        if (request.enabled()) {
            GithubChecksSetupStatusDto readiness = status(owner, repo);
            if (!readiness.ready()) {
                throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "GitHub App/Checks 前置自检未通过，不能启用该仓库的 Check Run"
                );
            }
        }
        policyService.setEnabled(owner, repo, request.enabled(), request.expectedVersion(), operator);
        return status(owner, repo);
    }

    private Probe probe(
        String organization,
        String repository,
        List<GithubChecksDiagnosticDto> diagnostics
    ) {
        TenantRepositoryBinding binding = null;
        try {
            binding = tenantRepositoryResolver.resolve(organization, repository, null);
        } catch (RuntimeException exception) {
            diagnostics.add(diagnostic(
                "repository_tenant_binding", "租户仓库授权", "danger",
                "目标仓库没有有效的租户绑定：" + conciseError(exception), true
            ));
        }

        boolean appEnabled = appProperties.isEnabled();
        boolean appConfigured = appProperties.isConfigured();
        if (!appEnabled) {
            diagnostics.add(diagnostic(
                "github_app_disabled", "GitHub App", "warning",
                "GitHub App 未启用；个人 PAT 仍可用于普通审查，但 Checks 向导不可用", true
            ));
        } else if (!appConfigured) {
            diagnostics.add(diagnostic(
                "github_app_not_configured", "GitHub App", "danger",
                "App ID 或私钥未完整配置；向导不会回显或记录私钥", true
            ));
        } else {
            diagnostics.add(diagnostic(
                "github_app_configured", "GitHub App", "success", "App 配置完整（私钥已隐藏）", false
            ));
        }

        Long installationId = binding == null ? null : binding.githubInstallationId();
        boolean installationAllowlisted = appProperties.isInstallationAllowlisted(installationId);
        if (appEnabled && installationId == null) {
            diagnostics.add(diagnostic(
                "installation_missing", "App installation", "danger",
                "租户仓库没有 GitHub App installation 映射", true
            ));
        } else if (appEnabled && !installationAllowlisted) {
            diagnostics.add(diagnostic(
                "installation_not_allowlisted", "App installation", "danger",
                "installation 不在服务端 allowlist 中或已被移除", true
            ));
        } else if (appEnabled && appConfigured) {
            diagnostics.add(diagnostic(
                "installation_present", "App installation", "success", "installation 映射和 allowlist 校验通过", false
            ));
        }

        GithubIntegrationSettings settings = null;
        GithubCheckRunGateway.InstallationInspection inspection = new GithubCheckRunGateway.InstallationInspection(
            false, java.util.Map.of()
        );
        if (appEnabled && appConfigured && installationAllowlisted) {
            try {
                settings = integrationProvider.getSettingsForRepository(organization, repository);
                if (settings == null || !StringUtils.hasText(settings.token())) {
                    diagnostics.add(diagnostic(
                        "github_token_missing", "GitHub 凭证", "danger", "未能取得 installation token", true
                    ));
                } else {
                    GithubIntegrationSettings trustedSettings = settings;
                    inspection = healthReporter.recordReadOperation(
                        trustedSettings,
                        "checks.setup.installation.inspect",
                        () -> gateway.inspectInstallation(
                            trustedSettings, trustedSettings.baseUrl(), organization, repository
                        )
                    );
                    if (!inspection.repositoryAuthorized()) {
                        diagnostics.add(diagnostic(
                            "repository_not_authorized", "目标仓库授权", "danger",
                            "GitHub App installation 未授权该仓库", true
                        ));
                    } else {
                        diagnostics.add(diagnostic(
                            "repository_authorized", "目标仓库授权", "success", "目标仓库在 installation 授权列表中", false
                        ));
                    }
                }
            } catch (RuntimeException exception) {
                diagnostics.add(externalDiagnostic(exception));
            }
        } else {
            try {
                GithubIntegrationSettings candidate = integrationProvider.getSettingsForRepository(organization, repository);
                if (candidate != null && StringUtils.hasText(candidate.token())) {
                    diagnostics.add(diagnostic(
                        "personal_pat_available", "个人 PAT 路径", "info",
                        "已配置 PAT；可继续使用十分钟首次审查路径", false
                    ));
                }
            } catch (RuntimeException exception) {
                diagnostics.add(diagnostic(
                    "personal_pat_unavailable", "个人 PAT 路径", "info", "PAT 未配置或无法读取", false
                ));
            }
        }

        setupDiagnostics.addPermissionDiagnostics(inspection, diagnostics);
        setupDiagnostics.addGlobalCheckDiagnostics(diagnostics);
        setupDiagnostics.addWebhookDiagnostics(organization, repository, diagnostics);
        boolean ready = appEnabled && appConfigured && installationId != null && installationAllowlisted
            && inspection.repositoryAuthorized()
            && List.of("metadata", "contents", "pull_requests", "checks").stream().allMatch(inspection::hasPermission)
            && checkRunProperties.isEnabled() && setupDiagnostics.webhookReady();
        String baseUrl = settings == null ? null : settings.baseUrl();
        return new Probe(
            appEnabled, appConfigured, installationId, installationAllowlisted, inspection, settings, baseUrl, ready
        );
    }

    private GithubChecksSetupStatusDto assemble(
        String organization,
        String repository,
        Probe probe,
        List<GithubChecksDiagnosticDto> diagnostics,
        GithubChecksPreviewDto preview
    ) {
        GithubCheckRunPolicy policy = policyService.find(organization, repository);
        boolean repositoryEnabled = policy != null && Boolean.TRUE.equals(policy.getEnabled());
        long policyVersion = policy == null || policy.getPolicyVersion() == null ? 0 : policy.getPolicyVersion();
        return new GithubChecksSetupStatusDto(
            organization,
            repository,
            probe.appEnabled(),
            probe.appConfigured(),
            probe.installationId(),
            probe.installationAllowlisted(),
            probe.inspection().repositoryAuthorized(),
            probe.inspection().hasPermission("metadata"),
            probe.inspection().hasPermission("contents"),
            probe.inspection().hasPermission("pull_requests"),
            probe.inspection().hasPermission("checks"),
            checkRunProperties.isEnabled(),
            repositoryEnabled,
            probe.ready() && repositoryEnabled,
            policyVersion,
            setupDiagnostics.webhookStatus(organization, repository),
            diagnostics,
            preview,
            probe.ready(),
            "合并门禁只需在 GitHub 仓库 Ruleset/Branch protection 中手动选择 RepoGuard PR Review；RepoGuard 不会自动修改 branch protection。"
        );
    }

    private GithubCheckRunGateway.PullRequestHead readHead(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        int pullRequestNumber
    ) {
        return healthReporter.recordReadOperation(
            settings,
            "checks.setup.preview.pull_request",
            () -> gateway.pullRequestHead(settings, baseUrl, owner, repository, pullRequestNumber)
        );
    }

    private GithubChecksPreviewDto failedPreview(String headSha, String status, String message) {
        return new GithubChecksPreviewDto(
            true, false, headSha, null, null, "PREVIEW", 1, null, 0,
            0, 0, false, status, null, message
        );
    }

    private GithubChecksDiagnosticDto externalDiagnostic(RuntimeException exception) {
        if (exception instanceof ExternalCallException external) {
            String status = external.isRetryable() ? "warning" : "danger";
            String message = external.getCategory();
            if (external.getStatusCode() != null) {
                message += "（HTTP " + external.getStatusCode() + "）";
            }
            return diagnostic(
                external.getCategory(), "GitHub API", status, message + "；可重试=" + external.isRetryable(),
                !external.isRetryable()
            );
        }
        return diagnostic("github_probe_failed", "GitHub API", "danger", conciseError(exception), true);
    }

    private GithubChecksDiagnosticDto diagnostic(
        String code,
        String label,
        String status,
        String message,
        boolean blocking
    ) {
        return new GithubChecksDiagnosticDto(code, label, status, message, blocking);
    }

    private String conciseError(RuntimeException exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 237) + "..." : message;
    }

    private String normalize(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
            || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " has an invalid format");
        }
        return normalized;
    }

    private record Probe(
        boolean appEnabled,
        boolean appConfigured,
        Long installationId,
        boolean installationAllowlisted,
        GithubCheckRunGateway.InstallationInspection inspection,
        GithubIntegrationSettings settings,
        String baseUrl,
        boolean ready
    ) {
    }
}
