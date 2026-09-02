package com.repoguard.agent.github.checks;

import com.repoguard.agent.dto.GithubChecksDiagnosticDto;
import com.repoguard.agent.dto.GithubChecksWebhookStatusDto;
import com.repoguard.agent.github.webhook.GithubWebhookDeliveryTracker;
import com.repoguard.agent.github.webhook.GithubWebhookProperties;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Builds safe, operator-facing setup diagnostics without exposing secrets or payloads. */
@Component
public class GithubChecksSetupDiagnostics {

    private static final List<String> REQUIRED_PERMISSIONS = List.of(
        "metadata", "contents", "pull_requests", "checks"
    );

    private final GithubCheckRunProperties checkRunProperties;
    private final GithubWebhookProperties webhookProperties;
    private final GithubWebhookDeliveryTracker deliveryTracker;

    public GithubChecksSetupDiagnostics(
        GithubCheckRunProperties checkRunProperties,
        GithubWebhookProperties webhookProperties,
        GithubWebhookDeliveryTracker deliveryTracker
    ) {
        this.checkRunProperties = checkRunProperties;
        this.webhookProperties = webhookProperties;
        this.deliveryTracker = deliveryTracker;
    }

    public void addPermissionDiagnostics(
        GithubCheckRunGateway.InstallationInspection inspection,
        List<GithubChecksDiagnosticDto> diagnostics
    ) {
        for (String permission : REQUIRED_PERMISSIONS) {
            boolean granted = inspection.hasPermission(permission);
            diagnostics.add(diagnostic(
                "permission_" + permission,
                permission + " 权限",
                granted ? "success" : "danger",
                granted ? "已授予" : "缺少或未授权",
                !granted
            ));
        }
    }

    public void addGlobalCheckDiagnostics(List<GithubChecksDiagnosticDto> diagnostics) {
        boolean enabled = checkRunProperties.isEnabled();
        diagnostics.add(diagnostic(
            "check_run_global_flag",
            "全局 Check Run 开关",
            enabled ? "success" : "danger",
            enabled ? "已启用" : "全局配置关闭，需先设置 REPOGUARD_GITHUB_CHECK_RUN_ENABLED=true",
            !enabled
        ));
    }

    public void addWebhookDiagnostics(
        String organization,
        String repository,
        List<GithubChecksDiagnosticDto> diagnostics
    ) {
        if (!webhookProperties.isEnabled()) {
            diagnostics.add(diagnostic(
                "webhook_disabled", "Webhook", "danger", "Webhook 未启用，无法接收 rerequested", true
            ));
        }
        if (!webhookProperties.isRequireSignature()) {
            diagnostics.add(diagnostic(
                "webhook_signature_disabled", "Webhook 签名", "danger", "签名校验关闭，生产环境不允许", true
            ));
        } else if (!StringUtils.hasText(webhookProperties.getSecret())) {
            diagnostics.add(diagnostic(
                "webhook_secret_missing", "Webhook 签名", "danger", "签名 secret 未配置且不会在页面回显", true
            ));
        } else {
            diagnostics.add(diagnostic(
                "webhook_signature_ready", "Webhook 签名", "success", "HMAC-SHA256 签名校验已启用", false
            ));
        }
        boolean repoRestricted = webhookProperties.getAllowedRepositories().stream().anyMatch(StringUtils::hasText);
        boolean branchRestricted = webhookProperties.getAllowedHeadBranches().stream().anyMatch(StringUtils::hasText);
        if (!repoRestricted) {
            diagnostics.add(diagnostic(
                "webhook_repository_allowlist_missing", "Webhook 仓库白名单", "danger",
                "未限制允许仓库，生产环境可能接收任意仓库", true
            ));
        }
        if (!branchRestricted) {
            diagnostics.add(diagnostic(
                "webhook_branch_allowlist_missing", "Webhook 分支白名单", "warning",
                "未限制允许 head 分支，建议显式配置", false
            ));
        }
        GithubWebhookDeliveryTracker.Delivery delivery = deliveryTracker.latestFor(organization, repository);
        if (delivery == null) {
            diagnostics.add(diagnostic(
                "webhook_delivery_missing", "最近 delivery", "warning",
                "尚未观测到该仓库 delivery；只显示脱敏 ID，发送测试 webhook 后可复核", false
            ));
        } else if (delivery.status().startsWith("rejected_")) {
            diagnostics.add(diagnostic(
                "webhook_delivery_rejected", "最近 delivery", "danger",
                "最近 delivery 被拒绝（常见原因是签名、限流或 payload 校验）", true
            ));
        } else {
            diagnostics.add(diagnostic(
                "webhook_delivery_ready", "最近 delivery", "success",
                "最近 delivery 已接收：" + delivery.status(), false
            ));
        }
    }

    public GithubChecksWebhookStatusDto webhookStatus(String organization, String repository) {
        GithubWebhookDeliveryTracker.Delivery delivery = deliveryTracker.latestFor(organization, repository);
        return new GithubChecksWebhookStatusDto(
            webhookProperties.getEndpointUrl(),
            webhookProperties.isEnabled(),
            webhookProperties.isRequireSignature(),
            StringUtils.hasText(webhookProperties.getSecret()),
            webhookProperties.getAllowedRepositories().stream().anyMatch(StringUtils::hasText),
            webhookProperties.getAllowedHeadBranches().stream().anyMatch(StringUtils::hasText),
            delivery == null ? null : delivery.deliveryId(),
            delivery == null ? "NOT_OBSERVED" : delivery.status(),
            delivery == null ? null : delivery.receivedAt().toString()
        );
    }

    public boolean webhookReady() {
        return webhookProperties.isEnabled()
            && webhookProperties.isRequireSignature()
            && StringUtils.hasText(webhookProperties.getSecret())
            && webhookProperties.getAllowedRepositories().stream().anyMatch(StringUtils::hasText);
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
}
