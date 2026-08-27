package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.tenancy.EnterpriseTenantAdminService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprise/tenants")
@ApiRuntimeEnabled
@RequireRole({"ADMIN"})
public class EnterpriseTenantController {

    private final EnterpriseTenantAdminService adminService;

    public EnterpriseTenantController(EnterpriseTenantAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping
    public ApiResponse<EnterpriseTenantDto> create(
        @Valid @RequestBody EnterpriseTenantCreateRequest request,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        return ApiResponse.ok(adminService.create(request));
    }

    @PutMapping("/{tenantKey}/memberships")
    public ApiResponse<Void> putMembership(
        @PathVariable String tenantKey,
        @Valid @RequestBody EnterpriseTenantMembershipRequest request,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        adminService.putMembership(tenantKey, request);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{tenantKey}/repositories")
    public ApiResponse<Void> putRepository(
        @PathVariable String tenantKey,
        @Valid @RequestBody EnterpriseTenantRepositoryRequest request,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        adminService.putRepository(tenantKey, request);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{tenantKey}/identities")
    public ApiResponse<Void> putIdentity(
        @PathVariable String tenantKey,
        @Valid @RequestBody EnterpriseIdentityBindingRequest request,
        HttpServletRequest httpRequest
    ) {
        requirePlatformAdmin(httpRequest);
        adminService.putIdentity(tenantKey, request);
        return ApiResponse.ok(null);
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        var principal = RequestAuthentication.require(request);
        if (!Long.valueOf(0L).equals(principal.id()) || !"admin-api-key".equals(principal.username())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Platform administrator API key is required");
        }
    }
}
