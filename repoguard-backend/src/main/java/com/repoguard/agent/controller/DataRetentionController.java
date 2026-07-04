package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.DataRetentionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config/data-retention")
@ApiRuntimeEnabled
public class DataRetentionController {

    private final DataRetentionService dataRetentionService;

    public DataRetentionController(DataRetentionService dataRetentionService) {
        this.dataRetentionService = dataRetentionService;
    }

    @PostMapping("/cleanup")
    @RequireRole("ADMIN")
    public ApiResponse<DataRetentionCleanupResponse> cleanup(
        @Valid @RequestBody(required = false) DataRetentionCleanupRequest request
    ) {
        return ApiResponse.ok(dataRetentionService.cleanup(request));
    }
}
