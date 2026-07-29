package com.repoguard.agent.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityAccountLifecycle.PasswordChangeCommand;
import com.repoguard.agent.identity.IdentityAccountLifecycle.RegistrationCommand;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.IdentitySessionTokens;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.PasswordHashService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class DefaultIdentityAccountLifecycleTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final IdentityAuditRecorder auditRecorder = Mockito.mock(IdentityAuditRecorder.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final IdentitySessionLifecycle sessionLifecycle = Mockito.mock(IdentitySessionLifecycle.class);
    private final AuthProperties authProperties = new AuthProperties();
    private final AuthAccountCache authAccountCache = Mockito.mock(AuthAccountCache.class);
    private final DefaultIdentityAccountLifecycle lifecycle = new DefaultIdentityAccountLifecycle(
        userAccountMapper,
        auditRecorder,
        passwordHashService,
        sessionLifecycle,
        authProperties,
        authAccountCache
    );

    @Test
    void registrationRejectsPasswordConfirmationMismatchBeforePersistence() {
        assertThatThrownBy(() -> lifecycle.register(new RegistrationCommand(
            "admin",
            "admin@repoguard.dev",
            "Secure123",
            "Different123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("两次输入的密码不一致");

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void registrationRejectsWeakPasswordBeforeAccountLookup() {
        assertThatThrownBy(() -> lifecycle.register(new RegistrationCommand(
            "admin",
            "admin@repoguard.dev",
            "lettersOnly",
            "lettersOnly"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("密码至少 8 位，且必须同时包含字母和数字");

        verify(userAccountMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void registrationRejectsDuplicateEmailAfterUsernameIsAvailable() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null, activeUser());

        assertThatThrownBy(() -> lifecycle.register(new RegistrationCommand(
            "new-admin",
            "ADMIN@REPOGUARD.DEV",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("邮箱已存在");

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void registrationTranslatesConcurrentUniqueKeyConflict() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAccountMapper.insert(any(UserAccount.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> lifecycle.register(new RegistrationCommand(
            "admin",
            "admin@repoguard.dev",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户名或邮箱已存在");

        verify(sessionLifecycle, never()).issue(any(IdentityAccount.class), eq(false));
    }

    @Test
    void currentProfileRejectsMissingAccount() {
        when(userAccountMapper.selectById(1001L)).thenReturn(null);

        assertThatThrownBy(() -> lifecycle.currentProfile(1001L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号不可用，请重新登录");
    }

    @Test
    void passwordChangeRejectsConfirmationMismatch() {
        UserAccount user = activeUserWithPassword("Secure123");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> lifecycle.changePassword(1001L, new PasswordChangeCommand(
            "Secure123",
            "Safer456",
            "Different456"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("New password and confirmation do not match");
    }

    @Test
    void passwordChangeRejectsWeakNewPassword() {
        UserAccount user = activeUserWithPassword("Secure123");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> lifecycle.changePassword(1001L, new PasswordChangeCommand(
            "Secure123",
            "lettersOnly",
            "lettersOnly"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("New password must contain both letters and numbers");
    }

    @Test
    void passwordChangeRejectsCurrentPasswordReuse() {
        UserAccount user = activeUserWithPassword("Secure123");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> lifecycle.changePassword(1001L, new PasswordChangeCommand(
            "Secure123",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("New password must differ from the current password");
    }

    @Test
    void passwordChangeRevokesRefreshTokensAfterAtomicPasswordAndVersionUpdate() {
        UserAccount user = activeUserWithPassword("Secure123");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        when(userAccountMapper.updatePasswordAndRotateSession(
            eq(1001L),
            eq(user.getPasswordHash()),
            any(String.class),
            any(LocalDateTime.class)
        )).thenReturn(1);

        lifecycle.changePassword(1001L, new PasswordChangeCommand(
            "Secure123",
            "Safer456",
            "Safer456"
        ));

        verify(authAccountCache).invalidateAfterCommit(1001L);
        verify(sessionLifecycle).invalidateAccountSessions(
            eq(1001L),
            eq(SessionInvalidationMode.REFRESH_TOKENS_ONLY),
            any(LocalDateTime.class)
        );
        verify(auditRecorder).record(1001L, "admin", "PASSWORD_CHANGE", "SUCCESS", null);
    }

    @Test
    void registrationUsesIsolatedTransactionAndComposesSessionIssuance() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        DefaultIdentityAccountLifecycle transactionalLifecycle = new DefaultIdentityAccountLifecycle(
            userAccountMapper,
            auditRecorder,
            passwordHashService,
            sessionLifecycle,
            authProperties,
            authAccountCache,
            transactionManager
        );
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAccountMapper.insert(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId(1001L);
            return 1;
        });
        when(sessionLifecycle.issue(any(IdentityAccount.class), eq(false))).thenAnswer(invocation -> {
            IdentityAccount account = invocation.getArgument(0);
            return tokens(account);
        });

        IdentitySessionTokens session = transactionalLifecycle.register(new RegistrationCommand(
            "admin",
            "ADMIN@REPOGUARD.DEV",
            "Secure123",
            "Secure123"
        ));

        assertThat(session.account().email()).isEqualTo("admin@repoguard.dev");
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
        assertThat(transactionManager.lastPropagationBehavior)
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(auditRecorder).record(1001L, "admin", "REGISTER", "SUCCESS", null);
    }

    private IdentitySessionTokens tokens(IdentityAccount account) {
        return new IdentitySessionTokens(
            "access-token",
            "refresh-token",
            "Bearer",
            900,
            3600,
            account
        );
    }

    private UserAccount activeUserWithPassword(String password) {
        UserAccount user = activeUser();
        user.setPasswordHash(passwordHashService.hash(password));
        return user;
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId(1001L);
        user.setUsername("admin");
        user.setEmail("admin@repoguard.dev");
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        user.setFailedLoginCount(0);
        user.setSessionVersion(0);
        user.setCreatedAt(LocalDateTime.parse("2026-07-19T12:00:00"));
        user.setUpdatedAt(LocalDateTime.parse("2026-07-19T12:00:00"));
        return user;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private int commitCount;
        private int rollbackCount;
        private int lastPropagationBehavior = -1;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lastPropagationBehavior = definition.getPropagationBehavior();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
        }
    }
}
