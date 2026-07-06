package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.user.UserAccountSessionInvalidator;
import com.repoguard.agent.user.UserManagementDisplayMapper;
import com.repoguard.agent.user.UserOperationAuditRecorder;
import com.repoguard.agent.user.UserRoleStatusPolicy;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserManagementServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final UserOperationAuditMapper userOperationAuditMapper = Mockito.mock(UserOperationAuditMapper.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final UserManagementDisplayMapper displayMapper = new UserManagementDisplayMapper();
    private final UserOperationAuditRecorder auditRecorder = new UserOperationAuditRecorder(
        userAccountMapper,
        userOperationAuditMapper
    );
    private final UserAccountSessionInvalidator sessionInvalidator =
        new UserAccountSessionInvalidator(userRefreshTokenMapper);
    private final UserRoleStatusPolicy roleStatusPolicy = new UserRoleStatusPolicy();
    private final UserManagementServiceImpl userManagementService = new UserManagementServiceImpl(
        userAccountMapper,
        userOperationAuditMapper,
        passwordHashService,
        displayMapper,
        auditRecorder,
        sessionInvalidator,
        roleStatusPolicy
    );

    @BeforeEach
    void setUp() {
        Mockito.reset(userAccountMapper, userRefreshTokenMapper, userOperationAuditMapper);
    }

    @Test
    void listUsersReturnsUserManagementItems() {
        when(userAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(user(1001L, "admin", "ADMIN", "ACTIVE")));

        var users = userManagementService.listUsers();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).username()).isEqualTo("admin");
        assertThat(users.get(0).role()).isEqualTo("ADMIN");
    }

    @Test
    void listOperationAuditsReturnsRecentAuditItems() {
        UserOperationAudit audit = new UserOperationAudit();
        audit.setId(9001L);
        audit.setOperatorUserId(1001L);
        audit.setOperatorUsername("admin");
        audit.setTargetUserId(1002L);
        audit.setTargetUsername("viewer");
        audit.setAction("ROLE_UPDATE");
        audit.setBeforeValue("ADMIN");
        audit.setAfterValue("VIEWER");
        audit.setClientIp("10.0.0.1");
        audit.setUserAgent("JUnit");
        audit.setCreatedAt(LocalDateTime.parse("2026-06-11T10:30:00"));
        when(userOperationAuditMapper.selectList(any(Wrapper.class))).thenReturn(List.of(audit));

        var audits = userManagementService.listOperationAudits();

        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).operatorUsername()).isEqualTo("admin");
        assertThat(audits.get(0).targetUsername()).isEqualTo("viewer");
        assertThat(audits.get(0).action()).isEqualTo("ROLE_UPDATE");
    }

    @Test
    void createUserStoresViewerAccountAndAuditRecord() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));
        when(userAccountMapper.insert(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId(1003L);
            return 1;
        });

        var created = userManagementService.createUser(auditContext(), new UserCreateRequest(
            "reviewer",
            "Reviewer@RepoGuard.dev",
            "Secure123",
            "Secure123"
        ));

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).insert(userCaptor.capture());
        UserAccount saved = userCaptor.getValue();
        assertThat(saved.getUsername()).isEqualTo("reviewer");
        assertThat(saved.getEmail()).isEqualTo("reviewer@repoguard.dev");
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).doesNotContain("Secure123");
        assertThat(saved.getRole()).isEqualTo("VIEWER");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSessionVersion()).isZero();
        assertThat(created.role()).isEqualTo("VIEWER");
        assertThat(created.status()).isEqualTo("ACTIVE");

        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("USER_CREATE");
        assertThat(auditCaptor.getValue().getOperatorUsername()).isEqualTo("admin");
        assertThat(auditCaptor.getValue().getTargetUsername()).isEqualTo("reviewer");
        assertThat(auditCaptor.getValue().getAfterValue()).isEqualTo("VIEWER");
    }

    @Test
    void createUserRejectsDuplicateUsername() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user(1003L, "reviewer", "VIEWER", "ACTIVE"));

        assertThatThrownBy(() -> userManagementService.createUser(auditContext(), new UserCreateRequest(
            "reviewer",
            "reviewer2@repoguard.dev",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户名已存在");

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void updateRoleDemotesAdminWhenAnotherActiveAdminExists() {
        UserAccount user = user(1001L, "admin", "ADMIN", "ACTIVE");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        when(userAccountMapper.selectById(1002L)).thenReturn(user(1002L, "operator", "ADMIN", "ACTIVE"));
        when(userAccountMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var updated = userManagementService.updateRole(new UserOperationAuditContext(1002L, "10.0.0.1", "JUnit"), 1001L, "VIEWER");

        assertThat(updated.role()).isEqualTo("VIEWER");
        assertThat(user.getSessionVersion()).isEqualTo(1);
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("ROLE_UPDATE");
        assertThat(auditCaptor.getValue().getBeforeValue()).isEqualTo("ADMIN");
        assertThat(auditCaptor.getValue().getAfterValue()).isEqualTo("VIEWER");
        assertThat(auditCaptor.getValue().getOperatorUsername()).isEqualTo("operator");
        assertThat(auditCaptor.getValue().getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void updateRoleRejectsDemotingLastActiveAdmin() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));
        when(userAccountMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> userManagementService.updateRole(auditContext(), 1001L, "VIEWER"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("At least one active administrator is required");
    }

    @Test
    void updateStatusRejectsDisablingCurrentUser() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        assertThatThrownBy(() -> userManagementService.updateStatus(new UserOperationAuditContext(1001L, "10.0.0.1", "JUnit"), 1001L, "DISABLED"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot disable your own account");
    }

    @Test
    void updateStatusDisablesUserAndRevokesRefreshTokens() {
        UserAccount user = user(1003L, "viewer", "VIEWER", "ACTIVE");
        when(userAccountMapper.selectById(1003L)).thenReturn(user);
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        var updated = userManagementService.updateStatus(auditContext(), 1003L, "DISABLED");

        assertThat(updated.status()).isEqualTo("DISABLED");
        assertThat(user.getSessionVersion()).isEqualTo(1);
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("STATUS_UPDATE");
        assertThat(auditCaptor.getValue().getBeforeValue()).isEqualTo("ACTIVE");
        assertThat(auditCaptor.getValue().getAfterValue()).isEqualTo("DISABLED");
    }

    @Test
    void updateStatusReactivatesUserAndClearsLockState() {
        UserAccount user = user(1003L, "viewer", "VIEWER", "DISABLED");
        user.setFailedLoginCount(5);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(userAccountMapper.selectById(1003L)).thenReturn(user);
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        var updated = userManagementService.updateStatus(auditContext(), 1003L, "ACTIVE");

        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.failedLoginCount()).isZero();
        assertThat(updated.lockedUntil()).isNull();
        assertThat(user.getSessionVersion()).isEqualTo(1);
        verify(userAccountMapper).updateById(user);
        Mockito.verify(userRefreshTokenMapper, Mockito.never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void constructorRejectsMissingSessionInvalidator() {
        assertThatThrownBy(() -> new UserManagementServiceImpl(
            userAccountMapper,
            userOperationAuditMapper,
            passwordHashService,
            displayMapper,
            auditRecorder,
            null,
            roleStatusPolicy
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sessionInvalidator");
    }

    private UserOperationAuditContext auditContext() {
        return new UserOperationAuditContext(1001L, "10.0.0.1", "JUnit");
    }

    private UserAccount user(Long id, String username, String role, String status) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@repoguard.dev");
        user.setRole(role);
        user.setStatus(status);
        user.setFailedLoginCount(0);
        user.setCreatedAt(LocalDateTime.parse("2026-06-11T10:00:00"));
        user.setUpdatedAt(LocalDateTime.parse("2026-06-11T10:00:00"));
        return user;
    }
}
