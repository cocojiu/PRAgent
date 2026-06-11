package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserRoleUpdateRequest;
import com.repoguard.agent.dto.UserStatusUpdateRequest;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @PutMapping("/{id}/role")
    public ApiResponse<UserManagementItemDto> updateRole(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserRoleUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(userManagementService.updateRole(currentUserId(request), id, updateRequest.role()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UserManagementItemDto> updateStatus(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserStatusUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(userManagementService.updateStatus(currentUserId(request), id, updateRequest.status()));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object authenticatedUser = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticatedUser instanceof AuthTokenService.AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication token is required");
        }
        return user.id();
    }
}
