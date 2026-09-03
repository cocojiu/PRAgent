package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.dto.UserRoleUpdateRequest;
import com.repoguard.agent.dto.UserStatusUpdateRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.user.UserManagementLifecycle;
import com.repoguard.agent.user.UserManagementLifecycle.AuditContext;
import com.repoguard.agent.user.UserManagementLifecycle.CreateCommand;
import com.repoguard.agent.user.UserManagementLifecycle.ManagedUser;
import com.repoguard.agent.user.UserManagementLifecycle.OperationAudit;
import com.repoguard.agent.user.UserManagementLifecycle.PageRequest;
import com.repoguard.agent.user.UserManagementLifecycle.PageResult;
import com.repoguard.agent.user.UserManagementLifecycle.RoleChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.StatusChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.UserSearch;
import com.repoguard.agent.web.AuditClientIpResolver;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Objects;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequireRole({"ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN"})
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
@Validated
public class UserManagementController {

    private final UserManagementLifecycle lifecycle;
    private final AuditClientIpResolver clientIpResolver;

    public UserManagementController(UserManagementLifecycle lifecycle, AuditClientIpResolver clientIpResolver) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver must not be null");
    }

    @GetMapping
    public ApiResponse<PageResponse<UserManagementItemDto>> listUsers(
        @Min(1) @RequestParam(defaultValue = "1") int page,
        @Min(1) @Max(100) @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String role,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword
    ) {
        PageResult<ManagedUser> result = lifecycle.listUsers(
            new UserSearch(page, pageSize, role, status, keyword)
        );
        return ApiResponse.ok(toUserPage(result));
    }

    @GetMapping("/audits")
    public ApiResponse<PageResponse<UserOperationAuditDto>> listOperationAudits(
        @Min(1) @RequestParam(defaultValue = "1") int page,
        @Min(1) @Max(100) @RequestParam(defaultValue = "20") int pageSize
    ) {
        return ApiResponse.ok(toAuditPage(lifecycle.listOperationAudits(new PageRequest(page, pageSize))));
    }

    @PostMapping
    public ApiResponse<UserManagementItemDto> createUser(
        HttpServletRequest request,
        @Valid @RequestBody UserCreateRequest createRequest
    ) {
        return ApiResponse.ok(toUserItem(lifecycle.createUser(
            auditContext(request),
            new CreateCommand(
                createRequest.username(),
                createRequest.email(),
                createRequest.password(),
                createRequest.confirmPassword()
            )
        )));
    }

    @PutMapping("/{id}/role")
    public ApiResponse<UserManagementItemDto> updateRole(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserRoleUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(toUserItem(lifecycle.updateRole(
            auditContext(request),
            new RoleChangeCommand(id, updateRequest.role())
        )));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UserManagementItemDto> updateStatus(
        HttpServletRequest request,
        @PathVariable Long id,
        @Valid @RequestBody UserStatusUpdateRequest updateRequest
    ) {
        return ApiResponse.ok(toUserItem(lifecycle.updateStatus(
            auditContext(request),
            new StatusChangeCommand(id, updateRequest.status())
        )));
    }

    private AuditContext auditContext(HttpServletRequest request) {
        var user = RequestAuthentication.require(request);
        return new AuditContext(
            user.id(),
            clientIpResolver.resolve(request),
            truncate(request.getHeader("User-Agent"), 512)
        );
    }

    private PageResponse<UserManagementItemDto> toUserPage(PageResult<ManagedUser> result) {
        return new PageResponse<>(result.items().stream().map(this::toUserItem).toList(), result.total());
    }

    private PageResponse<UserOperationAuditDto> toAuditPage(PageResult<OperationAudit> result) {
        return new PageResponse<>(result.items().stream().map(this::toAuditItem).toList(), result.total());
    }

    private UserManagementItemDto toUserItem(ManagedUser user) {
        return new UserManagementItemDto(
            user.id(),
            user.username(),
            user.email(),
            user.role(),
            user.status(),
            user.failedLoginCount(),
            user.lockedUntil(),
            user.lastLoginAt(),
            user.createdAt(),
            user.updatedAt()
        );
    }

    private UserOperationAuditDto toAuditItem(OperationAudit audit) {
        return new UserOperationAuditDto(
            audit.id(),
            audit.operatorUserId(),
            audit.operatorUsername(),
            audit.targetUserId(),
            audit.targetUsername(),
            audit.action(),
            audit.beforeValue(),
            audit.afterValue(),
            audit.clientIp(),
            audit.userAgent(),
            audit.createdAt()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
