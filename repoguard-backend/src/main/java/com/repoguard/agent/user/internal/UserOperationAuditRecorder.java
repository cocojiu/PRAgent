package com.repoguard.agent.user.internal;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.user.UserManagementLifecycle.AuditContext;
import java.time.LocalDateTime;
import java.util.Objects;

final class UserOperationAuditRecorder {

    private static final int CLIENT_IP_MAX_LENGTH = 64;
    private static final int USER_AGENT_MAX_LENGTH = 512;

    private final UserAccountMapper userAccountMapper;
    private final UserOperationAuditMapper userOperationAuditMapper;

    UserOperationAuditRecorder(
        UserAccountMapper userAccountMapper,
        UserOperationAuditMapper userOperationAuditMapper
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.userOperationAuditMapper =
            Objects.requireNonNull(userOperationAuditMapper, "userOperationAuditMapper must not be null");
    }

    void record(
        AuditContext auditContext,
        UserAccount targetUser,
        String action,
        String beforeValue,
        String afterValue
    ) {
        UserOperationAudit audit = new UserOperationAudit();
        Long operatorId = auditContext == null ? null : auditContext.operatorId();
        UserAccount operator = operatorId == null ? null : userAccountMapper.selectById(operatorId);
        audit.setOperatorUserId(operatorId);
        audit.setOperatorUsername(operator == null ? null : operator.getUsername());
        audit.setTargetUserId(targetUser.getId());
        audit.setTargetUsername(targetUser.getUsername());
        audit.setAction(action);
        audit.setBeforeValue(beforeValue);
        audit.setAfterValue(afterValue);
        audit.setClientIp(auditContext == null ? null : truncate(auditContext.clientIp(), CLIENT_IP_MAX_LENGTH));
        audit.setUserAgent(auditContext == null ? null : truncate(auditContext.userAgent(), USER_AGENT_MAX_LENGTH));
        audit.setCreatedAt(LocalDateTime.now());
        userOperationAuditMapper.insert(audit);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
