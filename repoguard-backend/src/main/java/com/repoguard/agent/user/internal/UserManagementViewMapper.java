package com.repoguard.agent.user.internal;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.user.UserManagementLifecycle.ManagedUser;
import com.repoguard.agent.user.UserManagementLifecycle.OperationAudit;

final class UserManagementViewMapper {

    ManagedUser toManagedUser(UserAccount user) {
        return new ManagedUser(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getStatus(),
            user.getFailedLoginCount(),
            user.getLockedUntil(),
            user.getLastLoginAt(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    OperationAudit toOperationAudit(UserOperationAudit audit) {
        return new OperationAudit(
            audit.getId(),
            audit.getOperatorUserId(),
            audit.getOperatorUsername(),
            audit.getTargetUserId(),
            audit.getTargetUsername(),
            audit.getAction(),
            audit.getBeforeValue(),
            audit.getAfterValue(),
            audit.getClientIp(),
            audit.getUserAgent(),
            audit.getCreatedAt()
        );
    }
}
