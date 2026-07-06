package com.repoguard.agent.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserOperationAuditRecorderTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserOperationAuditMapper userOperationAuditMapper = Mockito.mock(UserOperationAuditMapper.class);
    private final UserOperationAuditRecorder recorder = new UserOperationAuditRecorder(
        userAccountMapper,
        userOperationAuditMapper
    );

    @Test
    void recordsAuditWithResolvedOperatorAndTruncatedContext() {
        UserAccount operator = user(1001L, "admin");
        UserAccount target = user(1002L, "viewer");
        when(userAccountMapper.selectById(1001L)).thenReturn(operator);
        String longClientIp = "1".repeat(80);
        String longUserAgent = "A".repeat(600);

        recorder.record(
            new UserOperationAuditContext(1001L, longClientIp, longUserAgent),
            target,
            "ROLE_UPDATE",
            "VIEWER",
            "ADMIN"
        );

        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        UserOperationAudit audit = auditCaptor.getValue();
        assertThat(audit.getOperatorUserId()).isEqualTo(1001L);
        assertThat(audit.getOperatorUsername()).isEqualTo("admin");
        assertThat(audit.getTargetUserId()).isEqualTo(1002L);
        assertThat(audit.getTargetUsername()).isEqualTo("viewer");
        assertThat(audit.getAction()).isEqualTo("ROLE_UPDATE");
        assertThat(audit.getBeforeValue()).isEqualTo("VIEWER");
        assertThat(audit.getAfterValue()).isEqualTo("ADMIN");
        assertThat(audit.getClientIp()).hasSize(64);
        assertThat(audit.getUserAgent()).hasSize(512);
        assertThat(audit.getCreatedAt()).isNotNull();
    }

    @Test
    void recordsAuditWithoutOperatorWhenContextIsMissing() {
        UserAccount target = user(1002L, "viewer");

        recorder.record(null, target, "STATUS_UPDATE", "ACTIVE", "DISABLED");

        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        UserOperationAudit audit = auditCaptor.getValue();
        assertThat(audit.getOperatorUserId()).isNull();
        assertThat(audit.getOperatorUsername()).isNull();
        assertThat(audit.getClientIp()).isNull();
        assertThat(audit.getUserAgent()).isNull();
        verify(userAccountMapper, never()).selectById(any());
    }

    private UserAccount user(Long id, String username) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
