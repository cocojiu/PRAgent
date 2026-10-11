package com.repoguard.agent.controller;

import java.util.Map;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.preflight.PreflightPreviewService;
import com.repoguard.agent.security.AllowAnonymous;
import com.repoguard.agent.security.AuthAttemptLimiter;
import com.repoguard.agent.security.PreflightCredentialService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.web.AuditClientIpResolver;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preflight")
@ApiRuntimeEnabled
public class PreflightController {
    private final PreflightPreviewService previews;
    private final PreflightCredentialService credentials;
    private final AuthAttemptLimiter limiter;
    private final AuditClientIpResolver clientIp;
    private final boolean enabled;

    public PreflightController(PreflightPreviewService previews, PreflightCredentialService credentials,
        AuthAttemptLimiter limiter, AuditClientIpResolver clientIp,
        @org.springframework.beans.factory.annotation.Value("${repoguard.preflight.enabled:false}") boolean enabled) {
        this.previews = previews; this.credentials = credentials; this.limiter = limiter; this.clientIp = clientIp;
        this.enabled = enabled;
    }

    @RequireRole({"VIEWER"})
    @PostMapping(value = "/credentials", consumes = "application/json", produces = "application/json")
    public ApiResponse<PreflightCredentialService.Issue> issue(
        @Valid @RequestBody CredentialRequest payload, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        requireEnabled();
        var principal = RequestAuthentication.require(request);
        limiter.requireAllowed("preflight-mint", principal.id().toString(), clientIp.resolve(request));
        return ApiResponse.ok(previews.issue(principal, payload.repository(), Boolean.TRUE.equals(payload.llmAllowed())));
    }

    @AllowAnonymous
    @PostMapping(value = "/preview", consumes = "application/json", produces = "application/json")
    public ApiResponse<Map<String, Object>> preview(HttpServletRequest request, HttpServletResponse response)
        throws java.io.IOException {
        response.setHeader("Cache-Control", "no-store");
        requireEnabled();
        var grant = credentials.verify(request.getHeader("Authorization"));
        limiter.requireAllowed("preflight-preview", grant.userId().toString(), clientIp.resolve(request));
        String tenantHeader = request.getHeader("X-Tenant-Key");
        if (tenantHeader != null) throw new BusinessException(ErrorCode.BAD_REQUEST, "Use the credential tenant");
        byte[] input = request.getInputStream().readNBytes(3 * 1024 * 1024 + 1);
        return ApiResponse.ok(previews.preview(grant, input, request.getHeader("Idempotency-Key")));
    }

    private void requireEnabled() {
        if (!enabled) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Online preflight is disabled");
    }

    public record CredentialRequest(@NotBlank @Size(max = 141) String repository, Boolean llmAllowed) { }
}
