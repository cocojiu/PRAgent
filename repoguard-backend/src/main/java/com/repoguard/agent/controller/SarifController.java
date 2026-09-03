package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.scanner.SarifFindingService;
import com.repoguard.agent.security.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/scanners/sarif")
@ApiRuntimeEnabled
public class SarifController {

    private final SarifFindingService service;

    public SarifController(SarifFindingService service) {
        this.service = service;
    }

    @PostMapping("/tasks/{taskId}/import")
    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "RULE_ADMIN", "REVIEWER"})
    public ApiResponse<SarifImportResponse> importFindings(
        @PathVariable @Min(1) Long taskId,
        @Valid @RequestBody SarifImportRequest request
    ) {
        return ApiResponse.ok(service.importFindings(taskId, request));
    }

    @GetMapping("/tasks/{taskId}/export")
    @RequireRole({"PLATFORM_ADMIN", "TENANT_ADMIN", "RULE_ADMIN", "REVIEWER", "READ_ONLY"})
    public ApiResponse<SarifExportDto> exportFindings(@PathVariable @Min(1) Long taskId) {
        return ApiResponse.ok(service.exportFindings(taskId));
    }
}
