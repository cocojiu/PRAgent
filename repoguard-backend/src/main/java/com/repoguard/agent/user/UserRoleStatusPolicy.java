package com.repoguard.agent.user;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.UserAccount;
import org.springframework.stereotype.Component;

@Component
public class UserRoleStatusPolicy {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    public String adminRole() {
        return ROLE_ADMIN;
    }

    public String viewerRole() {
        return ROLE_VIEWER;
    }

    public String activeStatus() {
        return STATUS_ACTIVE;
    }

    public String normalizeRole(String role) {
        if (ROLE_ADMIN.equals(role) || ROLE_VIEWER.equals(role)) {
            return role;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user role");
    }

    public String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_DISABLED.equals(status)) {
            return status;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user status");
    }

    public boolean isAdmin(UserAccount user) {
        return user != null && ROLE_ADMIN.equals(user.getRole());
    }

    public boolean isViewerRole(String role) {
        return ROLE_VIEWER.equals(role);
    }

    public boolean isActiveStatus(String status) {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isDisabledStatus(String status) {
        return STATUS_DISABLED.equals(status);
    }
}
