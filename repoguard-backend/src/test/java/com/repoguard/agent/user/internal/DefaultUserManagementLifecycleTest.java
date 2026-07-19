package com.repoguard.agent.user.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.identity.IdentitySessionInvalidator;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.user.UserManagementLifecycle.AuditContext;
import com.repoguard.agent.user.UserManagementLifecycle.CreateCommand;
import com.repoguard.agent.user.UserManagementLifecycle.PageRequest;
import com.repoguard.agent.user.UserManagementLifecycle.RoleChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.StatusChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.UserSearch;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;

class DefaultUserManagementLifecycleTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserOperationAuditMapper userOperationAuditMapper = Mockito.mock(UserOperationAuditMapper.class);
    private final IdentitySessionInvalidator sessionInvalidator = Mockito.mock(IdentitySessionInvalidator.class);
    private final PasswordHashService passwordHasher = new PasswordHashService();
    private final DefaultUserManagementLifecycle lifecycle = new DefaultUserManagementLifecycle(
        userAccountMapper,
        userOperationAuditMapper,
        passwordHasher,
        sessionInvalidator
    );

    @BeforeEach
    void setUp() {
        Mockito.reset(userAccountMapper, userOperationAuditMapper, sessionInvalidator);
        when(userAccountMapper.lockActiveAdminInvariant()).thenReturn("active_admin");
    }

    @Test
    void listUsersReturnsUserManagementItems() {
        Page<UserAccount> page = Page.of(2, 10);
        page.setRecords(List.of(user(1001L, "admin", "ADMIN", "ACTIVE")));
        page.setTotal(21L);
        when(userAccountMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        var users = lifecycle.listUsers(new UserSearch(2, 10, "ADMIN", "ACTIVE", "adm"));

        assertThat(users.total()).isEqualTo(21L);
        assertThat(users.items()).hasSize(1);
        assertThat(users.items().get(0).username()).isEqualTo("admin");
        assertThat(users.items().get(0).role()).isEqualTo("ADMIN");
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
        Page<UserOperationAudit> page = Page.of(1, 20);
        page.setRecords(List.of(audit));
        page.setTotal(1L);
        when(userOperationAuditMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        var audits = lifecycle.listOperationAudits(new PageRequest(1, 20));

        assertThat(audits.total()).isEqualTo(1L);
        assertThat(audits.items()).hasSize(1);
        assertThat(audits.items().get(0).operatorUsername()).isEqualTo("admin");
        assertThat(audits.items().get(0).targetUsername()).isEqualTo("viewer");
        assertThat(audits.items().get(0).action()).isEqualTo("ROLE_UPDATE");
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

        var created = lifecycle.createUser(auditContext(), new CreateCommand(
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
        when(userAccountMapper.selectOne(any(Wrapper.class)))
            .thenReturn(user(1003L, "reviewer", "VIEWER", "ACTIVE"));

        assertThatThrownBy(() -> lifecycle.createUser(auditContext(), new CreateCommand(
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

        var updated = lifecycle.updateRole(
            new AuditContext(1002L, "10.0.0.1", "JUnit"),
            new RoleChangeCommand(1001L, "VIEWER")
        );

        assertThat(updated.role()).isEqualTo("VIEWER");
        verify(userAccountMapper).updateById(user);
        ArgumentCaptor<UserOperationAudit> auditCaptor = ArgumentCaptor.forClass(UserOperationAudit.class);
        verify(userOperationAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("ROLE_UPDATE");
        assertThat(auditCaptor.getValue().getBeforeValue()).isEqualTo("ADMIN");
        assertThat(auditCaptor.getValue().getAfterValue()).isEqualTo("VIEWER");
        assertThat(auditCaptor.getValue().getOperatorUsername()).isEqualTo("operator");
        assertThat(auditCaptor.getValue().getClientIp()).isEqualTo("10.0.0.1");
        InOrder order = inOrder(userAccountMapper, sessionInvalidator);
        order.verify(userAccountMapper).lockActiveAdminInvariant();
        order.verify(userAccountMapper).selectById(1001L);
        order.verify(userAccountMapper).selectCount(any(Wrapper.class));
        order.verify(userAccountMapper).updateById(user);
        order.verify(sessionInvalidator).invalidateAccountSessions(
            eq(1001L),
            eq(SessionInvalidationMode.ALL_SESSIONS),
            any(LocalDateTime.class)
        );
    }

    @Test
    void updateRoleRejectsDemotingLastActiveAdmin() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));
        when(userAccountMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> lifecycle.updateRole(
            auditContext(),
            new RoleChangeCommand(1001L, "VIEWER")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("At least one active administrator is required");
    }

    @Test
    void updateStatusRejectsDisablingCurrentUser() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        assertThatThrownBy(() -> lifecycle.updateStatus(
            new AuditContext(1001L, "10.0.0.1", "JUnit"),
            new StatusChangeCommand(1001L, "DISABLED")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot disable your own account");
    }

    @Test
    void updateStatusDisablesUserAndRevokesRefreshTokens() {
        UserAccount user = user(1003L, "viewer", "VIEWER", "ACTIVE");
        when(userAccountMapper.selectById(1003L)).thenReturn(user);
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        var updated = lifecycle.updateStatus(auditContext(), new StatusChangeCommand(1003L, "DISABLED"));

        assertThat(updated.status()).isEqualTo("DISABLED");
        verify(userAccountMapper).updateById(user);
        verify(sessionInvalidator).invalidateAccountSessions(
            eq(1003L),
            eq(SessionInvalidationMode.ALL_SESSIONS),
            any(LocalDateTime.class)
        );
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

        var updated = lifecycle.updateStatus(auditContext(), new StatusChangeCommand(1003L, "ACTIVE"));

        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.failedLoginCount()).isZero();
        assertThat(updated.lockedUntil()).isNull();
        verify(userAccountMapper).updateById(user);
        verify(sessionInvalidator).invalidateAccountSessions(
            eq(1003L),
            eq(SessionInvalidationMode.SESSION_VERSION_ONLY),
            any(LocalDateTime.class)
        );
        verify(userAccountMapper, never()).lockActiveAdminInvariant();
    }

    @Test
    void updateStatusRejectsChangeWhenAdminGuardIsUnavailable() {
        when(userAccountMapper.lockActiveAdminInvariant()).thenReturn(null);

        assertThatThrownBy(() -> lifecycle.updateStatus(
            auditContext(),
            new StatusChangeCommand(1003L, "DISABLED")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Active administrator guard is unavailable");

        verify(userAccountMapper, never()).selectById(1003L);
    }

    @Test
    void constructorRejectsMissingSessionInvalidator() {
        assertThatThrownBy(() -> new DefaultUserManagementLifecycle(
            userAccountMapper,
            userOperationAuditMapper,
            passwordHasher,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sessionInvalidator");
    }

    @Test
    void supportsClassBasedSpringTransactionProxy() {
        ProxyFactory proxyFactory = new ProxyFactory(lifecycle);
        proxyFactory.setProxyTargetClass(true);

        assertThat(proxyFactory.getProxy()).isInstanceOf(DefaultUserManagementLifecycle.class);
    }

    private AuditContext auditContext() {
        return new AuditContext(1001L, "10.0.0.1", "JUnit");
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
