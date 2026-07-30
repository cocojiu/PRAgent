package com.repoguard.agent.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.common.TrustedProxyProperties;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator.AuthenticationOperation;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.web.AuditClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class DefaultIdentityCredentialAuthenticatorTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserLoginAuditMapper userLoginAuditMapper = Mockito.mock(UserLoginAuditMapper.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final IdentityAuditRecorder auditRecorder = new IdentityAuditRecorder(
        userLoginAuditMapper,
        new AuditClientIpResolver(new TrustedProxyClientIpResolver(new TrustedProxyProperties(), new SimpleMeterRegistry()))
    );
    private final DefaultIdentityCredentialAuthenticator authenticator =
        new DefaultIdentityCredentialAuthenticator(userAccountMapper, passwordHashService, auditRecorder);

    @Test
    void wrongPasswordAdvancesLockStateAndRecordsFailureAudit() {
        UserAccount user = activeUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authenticator.authenticate(
            "admin",
            "Wrong123",
            AuthenticationOperation.LOGIN
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        verify(userAccountMapper).recordFailedLogin(
            anyLong(),
            anyInt(),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo("LOGIN");
        assertThat(audit.getValue().getResult()).isEqualTo("FAILURE");
        assertThat(audit.getValue().getFailureReason()).isEqualTo("bad credentials");
    }

    @Test
    void loginSuccessClearsFailuresAndRecordsSuccessAudit() {
        UserAccount user = activeUser();
        user.setFailedLoginCount(7);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        LocalDateTime occurredAt = LocalDateTime.parse("2026-07-19T01:30:00");

        authenticator.recordSuccess(identityAccount(user), "admin", AuthenticationOperation.LOGIN, occurredAt);

        verify(userAccountMapper).update(isNull(), any());
        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo("LOGIN");
        assertThat(audit.getValue().getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void tokenResetSuccessPreservesLoginFailureState() {
        UserAccount user = activeUser();
        user.setFailedLoginCount(3);

        authenticator.recordSuccess(
            identityAccount(user),
            "admin",
            AuthenticationOperation.TOKEN_RESET,
            LocalDateTime.parse("2026-07-19T01:31:00")
        );

        assertThat(user.getFailedLoginCount()).isEqualTo(3);
        verify(userAccountMapper, never()).update(isNull(), any());
        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo("TOKEN_RESET");
        assertThat(audit.getValue().getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void lockedAccountIsRejectedWithoutIncrementingFailureCount() {
        UserAccount user = activeUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        user.setFailedLoginCount(20);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(userAccountMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authenticator.authenticate(
            "admin",
            "Secure123",
            AuthenticationOperation.LOGIN
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        verify(userAccountMapper, never()).recordFailedLogin(
            anyLong(),
            anyInt(),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getFailureReason()).isEqualTo("account locked");
    }

    @Test
    void disabledAccountIsRejectedAndAudited() {
        UserAccount user = activeUser();
        user.setStatus("DISABLED");
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authenticator.authenticate(
            "admin",
            "Secure123",
            AuthenticationOperation.LOGIN
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getFailureReason()).isEqualTo("account disabled");
    }

    @Test
    void unknownAccountUsesFailureTransactionWithoutUpdatingAccount() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        DefaultIdentityCredentialAuthenticator transactionalAuthenticator =
            new DefaultIdentityCredentialAuthenticator(
                userAccountMapper,
                passwordHashService,
                auditRecorder,
                transactionManager
            );
        when(userAccountMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> transactionalAuthenticator.authenticate(
            "missing",
            "Secure123",
            AuthenticationOperation.LOGIN
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        assertThat(transactionManager.commitCount).isEqualTo(1);
        verify(userAccountMapper, never()).recordFailedLogin(
            anyLong(),
            anyInt(),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
        ArgumentCaptor<UserLoginAudit> audit = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getUserId()).isNull();
        assertThat(audit.getValue().getFailureReason()).isEqualTo("bad credentials");
    }

    @Test
    void emailAccountIsNormalizedBeforeLookup() {
        UserAccount user = activeUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any())).thenReturn(user);

        IdentityAccount authenticated = authenticator.authenticate(
            "ADMIN@REPOGUARD.DEV",
            "Secure123",
            AuthenticationOperation.TOKEN_RESET
        );

        assertThat(authenticated.id()).isEqualTo(user.getId());
        assertThat(authenticated.username()).isEqualTo(user.getUsername());
        assertThat(authenticated.sessionVersion()).isZero();
        verify(userAccountMapper).selectOne(any());
    }

    private IdentityAccount identityAccount(UserAccount user) {
        return new IdentityAccount(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getSessionVersion() == null ? 0 : user.getSessionVersion()
        );
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
        return user;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private int commitCount;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
