package com.repoguard.agent.user;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User-owned application port for administrative account management.
 */
public interface UserManagementLifecycle {

    PageResult<ManagedUser> listUsers(UserSearch search);

    PageResult<OperationAudit> listOperationAudits(PageRequest pageRequest);

    ManagedUser createUser(AuditContext auditContext, CreateCommand command);

    ManagedUser updateRole(AuditContext auditContext, RoleChangeCommand command);

    ManagedUser updateStatus(AuditContext auditContext, StatusChangeCommand command);

    record UserSearch(int page, int pageSize, String role, String status, String keyword) {
    }

    record PageRequest(int page, int pageSize) {
    }

    record AuditContext(Long operatorId, String clientIp, String userAgent) {
    }

    record CreateCommand(
        String username,
        String email,
        String password,
        String confirmPassword
    ) {
    }

    record RoleChangeCommand(Long userId, String role) {
    }

    record StatusChangeCommand(Long userId, String status) {
    }

    record PageResult<T>(List<T> items, long total) {

        public PageResult {
            items = List.copyOf(items);
        }
    }

    record ManagedUser(
        Long id,
        String username,
        String email,
        String role,
        String status,
        Integer failedLoginCount,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    record OperationAudit(
        Long id,
        Long operatorUserId,
        String operatorUsername,
        Long targetUserId,
        String targetUsername,
        String action,
        String beforeValue,
        String afterValue,
        String clientIp,
        String userAgent,
        LocalDateTime createdAt
    ) {
    }
}
