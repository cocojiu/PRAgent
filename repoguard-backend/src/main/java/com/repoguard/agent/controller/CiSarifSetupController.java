package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.CiSarifSetupDto;
import com.repoguard.agent.scanner.CiSarifSetupService;
import com.repoguard.agent.security.RequireRole;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ApiRuntimeEnabled
@RequestMapping("/api/v1/scanners/sarif/ci/tasks")
@RequireRole({"ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "RULE_ADMIN"})
public class CiSarifSetupController {
    private final CiSarifSetupService service;

    public CiSarifSetupController(CiSarifSetupService service) {
        this.service = service;
    }

    @GetMapping("/{taskId}/setup")
    public ApiResponse<CiSarifSetupDto> getSetup(@PathVariable @Min(1) Long taskId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(service.get(taskId));
    }
}
