package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.DataRetentionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config/data-retention")
@ApiRuntimeEnabled
@RequireRole("ADMIN")
public class DataRetentionController {

    private final DataRetentionService dataRetentionService;

    public DataRetentionController(DataRetentionService dataRetentionService) {
        this.dataRetentionService = dataRetentionService;
    }

    @PostMapping("/cleanup")
    public ApiResponse<DataRetentionCleanupResponse> cleanup(
        @Valid @RequestBody(required = false) DataRetentionCleanupRequest request
    ) {
        return ApiResponse.ok(dataRetentionService.cleanup(request));
    }

    @GetMapping("/cleanup-audits")
    public ApiResponse<PageResponse<DataRetentionCleanupAuditDto>> listCleanupAudits(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 16) String mode,
        @RequestParam(required = false) @Size(max = 32) String status,
        @RequestParam(required = false) @Size(max = 128) String backupReference
    ) {
        return ApiResponse.ok(dataRetentionService.listCleanupAudits(page, pageSize, mode, status, backupReference));
    }
}
