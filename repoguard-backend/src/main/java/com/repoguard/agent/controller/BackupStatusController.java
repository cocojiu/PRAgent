package com.repoguard.agent.controller;

import com.repoguard.agent.backup.BackupStatusQuery;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.BackupStatusResponse;
import com.repoguard.agent.security.RequireRole;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "PLATFORM_ADMIN"})
public class BackupStatusController {
    private final BackupStatusQuery query;

    public BackupStatusController(BackupStatusQuery query) { this.query = query; }

    @GetMapping("/api/v1/system/backup-status")
    public ApiResponse<BackupStatusResponse> status(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(query.read());
    }
}
