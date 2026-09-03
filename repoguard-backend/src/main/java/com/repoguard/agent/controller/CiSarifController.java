package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.CiSarifCredentialResponse;
import com.repoguard.agent.dto.CiSarifUploadResponse;
import com.repoguard.agent.scanner.CiSarifUploadCredentialService;
import com.repoguard.agent.scanner.CiSarifUploadService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.security.AllowAnonymous;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CI-only SARIF credential minting and upload channel. */
@Validated
@RestController
@RequestMapping("/api/v1/scanners/sarif/ci")
@ApiRuntimeEnabled
public class CiSarifController {

    private final CiSarifUploadCredentialService credentialService;
    private final CiSarifUploadService uploadService;

    public CiSarifController(
        CiSarifUploadCredentialService credentialService,
        CiSarifUploadService uploadService
    ) {
        this.credentialService = credentialService;
        this.uploadService = uploadService;
    }

    @PostMapping("/tasks/{taskId}/credentials")
    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "RULE_ADMIN"})
    public ApiResponse<CiSarifCredentialResponse> issueCredential(
        @PathVariable @Min(1) Long taskId,
        @RequestParam @Min(1) Long attemptId
    ) {
        CiSarifUploadCredentialService.TokenIssue issue = credentialService.issue(taskId, attemptId);
        return ApiResponse.ok(new CiSarifCredentialResponse(
            issue.token(),
            issue.expiresAt(),
            issue.taskId(),
            issue.attemptId(),
            issue.organization(),
            issue.repository(),
            issue.prNumber(),
            issue.commitSha()
        ));
    }

    /**
     * Accepts raw SARIF JSON or a bounded zip artifact. Metadata stays in headers so CI can stream
     * the scanner output without putting credentials or large documents into query strings.
     */
    @PostMapping(
        value = "/tasks/{taskId}/upload",
        consumes = {"application/json", "application/zip", "application/octet-stream"}
    )
    @AllowAnonymous
    public ApiResponse<CiSarifUploadResponse> upload(
        @PathVariable @Min(1) Long taskId,
        @RequestHeader("X-RepoGuard-CI-Credential") @NotBlank @Size(max = 4096) String credential,
        @RequestHeader("X-RepoGuard-CI-Tool") @NotBlank @Size(max = 128) String toolName,
        @RequestHeader(value = "X-RepoGuard-CI-Tool-Version", required = false) @Size(max = 64) String toolVersion,
        @RequestHeader("X-RepoGuard-CI-Scan-Run") @NotBlank @Size(max = 128) String scanRunId,
        @RequestHeader("X-RepoGuard-CI-Commit-SHA") @NotBlank @Size(max = 64) String commitSha,
        @RequestHeader("X-RepoGuard-CI-Completed-At") @NotBlank @Size(max = 64) String completedAt,
        @RequestHeader(value = "Content-Type", required = false) String contentType,
        @RequestBody byte[] payload
    ) {
        return ApiResponse.ok(uploadService.upload(
            taskId,
            credential,
            toolName,
            toolVersion,
            scanRunId,
            commitSha,
            completedAt,
            contentType,
            payload
        ));
    }
}
