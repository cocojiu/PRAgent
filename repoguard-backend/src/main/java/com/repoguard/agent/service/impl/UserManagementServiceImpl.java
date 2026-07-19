package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.service.UserManagementService;
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
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class UserManagementServiceImpl implements UserManagementService {

    private final UserManagementLifecycle lifecycle;

    public UserManagementServiceImpl(UserManagementLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    @Override
    public PageResponse<UserManagementItemDto> listUsers(
        int page,
        int pageSize,
        String role,
        String status,
        String keyword
    ) {
        PageResult<ManagedUser> result = lifecycle.listUsers(
            new UserSearch(page, pageSize, role, status, keyword)
        );
        return new PageResponse<>(result.items().stream()
            .map(this::toUserItem)
            .toList(), result.total());
    }

    @Override
    public PageResponse<UserOperationAuditDto> listOperationAudits(int page, int pageSize) {
        PageResult<OperationAudit> result = lifecycle.listOperationAudits(new PageRequest(page, pageSize));
        return new PageResponse<>(result.items().stream()
            .map(this::toAuditItem)
            .toList(), result.total());
    }

    @Override
    public UserManagementItemDto createUser(UserOperationAuditContext auditContext, UserCreateRequest request) {
        return toUserItem(lifecycle.createUser(
            toAuditContext(auditContext),
            new CreateCommand(
                request.username(),
                request.email(),
                request.password(),
                request.confirmPassword()
            )
        ));
    }

    @Override
    public UserManagementItemDto updateRole(UserOperationAuditContext auditContext, Long userId, String role) {
        return toUserItem(lifecycle.updateRole(
            toAuditContext(auditContext),
            new RoleChangeCommand(userId, role)
        ));
    }

    @Override
    public UserManagementItemDto updateStatus(UserOperationAuditContext auditContext, Long userId, String status) {
        return toUserItem(lifecycle.updateStatus(
            toAuditContext(auditContext),
            new StatusChangeCommand(userId, status)
        ));
    }

    private AuditContext toAuditContext(UserOperationAuditContext context) {
        return context == null
            ? null
            : new AuditContext(context.operatorId(), context.clientIp(), context.userAgent());
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
}
