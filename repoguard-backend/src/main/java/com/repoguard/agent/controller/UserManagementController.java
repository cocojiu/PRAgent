package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.dto.UserRoleUpdateRequest;
import com.repoguard.agent.dto.UserStatusUpdateRequest;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.UserManagementService;
import com.repoguard.agent.web.AuditClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequireRole("ADMIN")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public ApiResponse<List<UserManagementItemDto>> listUsers() {
        return ApiResponse.ok(userManagementService.listUsers());
    }

    @GetMapping("/audits")
    public ApiResponse<List<UserOperationAuditDto>> listOperationAudits() {
        return ApiResponse.ok(userManagementService.listOperationAudits());
    }

    @PostMapping
    public ApiResponse<UserManagementItemDto> createUser(
        HttpServletRequest request,
        @Valid @RequestBody UserCreateRequest createRequest
    ) {
        return ApiResponse.ok(userManagementService.createUser(auditContext(request), createRequest));
    }

    @PutMapping("/{id}/role")
    public ApiResponse<UserManagementItemDto> updateRole(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserRoleUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(userManagementService.updateRole(auditContext(request), id, updateRequest.role()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UserManagementItemDto> updateStatus(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserStatusUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(userManagementService.updateStatus(auditContext(request), id, updateRequest.status()));
    }

    private UserOperationAuditContext auditContext(HttpServletRequest request) {
        Object authenticatedUser = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticatedUser instanceof AuthTokenService.AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication token is required");
        }
        return new UserOperationAuditContext(
            user.id(),
            AuditClientIpResolver.resolve(request),
            truncate(request.getHeader("User-Agent"), 512)
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
