package com.repoguard.agent.user.internal;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.UserAccount;
import java.util.Set;

final class UserRoleStatusPolicy {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_VIEWER = "VIEWER";
    private static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";
    private static final String ROLE_RULE_ADMIN = "RULE_ADMIN";
    private static final String ROLE_REVIEWER = "REVIEWER";
    private static final String ROLE_READ_ONLY = "READ_ONLY";
    private static final Set<String> SUPPORTED_ROLES = Set.of(
        ROLE_ADMIN,
        ROLE_VIEWER,
        ROLE_PLATFORM_ADMIN,
        ROLE_TENANT_ADMIN,
        ROLE_RULE_ADMIN,
        ROLE_REVIEWER,
        ROLE_READ_ONLY
    );
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    String adminRole() {
        return ROLE_ADMIN;
    }

    String viewerRole() {
        return ROLE_VIEWER;
    }

    String platformAdminRole() {
        return ROLE_PLATFORM_ADMIN;
    }

    String tenantAdminRole() {
        return ROLE_TENANT_ADMIN;
    }

    String ruleAdminRole() {
        return ROLE_RULE_ADMIN;
    }

    String reviewerRole() {
        return ROLE_REVIEWER;
    }

    String readOnlyRole() {
        return ROLE_READ_ONLY;
    }

    String activeStatus() {
        return STATUS_ACTIVE;
    }

    String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
        if (SUPPORTED_ROLES.contains(normalized)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user role");
    }

    String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_DISABLED.equals(status)) {
            return status;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user status");
    }

    boolean isAdmin(UserAccount user) {
        return user != null && isAdminRole(user.getRole());
    }

    boolean isViewerRole(String role) {
        return ROLE_VIEWER.equals(role) || ROLE_REVIEWER.equals(role) || ROLE_READ_ONLY.equals(role);
    }

    boolean isAdminRole(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_PLATFORM_ADMIN.equals(role);
    }

    Set<String> adminRoles() {
        return Set.of(ROLE_ADMIN, ROLE_PLATFORM_ADMIN);
    }

    boolean isActiveStatus(String status) {
        return STATUS_ACTIVE.equals(status);
    }

    boolean isDisabledStatus(String status) {
        return STATUS_DISABLED.equals(status);
    }
}
