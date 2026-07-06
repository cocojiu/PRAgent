package com.repoguard.agent.user;

import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;

public class UserManagementDisplayMapper {

    public UserManagementItemDto toUserItem(UserAccount user) {
        return new UserManagementItemDto(
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

    public UserOperationAuditDto toAuditItem(UserOperationAudit audit) {
        return new UserOperationAuditDto(
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
