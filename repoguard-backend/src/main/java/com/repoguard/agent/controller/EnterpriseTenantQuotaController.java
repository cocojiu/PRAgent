package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.EnterpriseTenantQuotaDto;
import com.repoguard.agent.dto.EnterpriseTenantQuotaRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.tenancy.TenantQuotaService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprise/tenants")
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
@RequireRole({"ADMIN"})
public class EnterpriseTenantQuotaController {

    private final TenantQuotaService quotaService;

    public EnterpriseTenantQuotaController(TenantQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/{tenantKey}/quota")
    public ApiResponse<EnterpriseTenantQuotaDto> get(
        @PathVariable String tenantKey,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        return ApiResponse.ok(quotaService.get(tenantKey));
    }

    @PutMapping("/{tenantKey}/quota")
    public ApiResponse<EnterpriseTenantQuotaDto> update(
        @PathVariable String tenantKey,
        @Valid @RequestBody EnterpriseTenantQuotaRequest request,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        return ApiResponse.ok(quotaService.update(tenantKey, request));
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        var principal = RequestAuthentication.require(request);
        if (!Long.valueOf(0L).equals(principal.id()) || !"admin-api-key".equals(principal.username())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Platform administrator API key is required");
        }
    }
}
