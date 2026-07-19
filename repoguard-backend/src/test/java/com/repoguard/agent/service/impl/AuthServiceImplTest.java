package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthPasswordChangeRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityAccountLifecycle;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.internal.DefaultIdentityAccountLifecycle;
import com.repoguard.agent.identity.internal.DefaultIdentityCredentialAuthenticator;
import com.repoguard.agent.identity.internal.DefaultIdentitySessionLifecycle;
import com.repoguard.agent.identity.internal.IdentityAuditRecorder;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final UserLoginAuditMapper userLoginAuditMapper = Mockito.mock(UserLoginAuditMapper.class);
    private final IdentityAuditRecorder auditRecorder = new IdentityAuditRecorder(userLoginAuditMapper);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final IdentityCredentialAuthenticator credentialAuthenticator =
        new DefaultIdentityCredentialAuthenticator(userAccountMapper, passwordHashService, auditRecorder);
    private final AuthProperties authProperties = new AuthProperties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final RepoGuardMetrics metrics = Mockito.mock(RepoGuardMetrics.class);
    private final IdentitySessionLifecycle sessionLifecycle = new DefaultIdentitySessionLifecycle(
        userAccountMapper,
        userRefreshTokenMapper,
        auditRecorder,
        credentialAuthenticator,
        authProperties,
        authTokenService,
        metrics
    );
    private final IdentityAccountLifecycle accountLifecycle = new DefaultIdentityAccountLifecycle(
        userAccountMapper,
        auditRecorder,
        passwordHashService,
        sessionLifecycle,
        authProperties
    );
    private final AuthServiceImpl authService = new AuthServiceImpl(
        accountLifecycle,
        credentialAuthenticator,
        sessionLifecycle
    );

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void registerStoresBCryptHashAndReturnsTokenPair() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAccountMapper.insert(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId(1001L);
            return 1;
        });

        AuthResponse response = authService.register(new AuthRegisterRequest(
            "admin",
            "Admin@RepoGuard.dev",
            "Secure123",
            "Secure123"
        ));

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).insert(userCaptor.capture());
        UserAccount saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("admin@repoguard.dev");
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).doesNotContain("Secure123");
        assertThat(saved.getRole()).isEqualTo("VIEWER");
        assertThat(saved.getFailedLoginCount()).isZero();
        assertThat(saved.getSessionVersion()).isZero();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("admin");
        assertThat(response.user().role()).isEqualTo("VIEWER");

        ArgumentCaptor<UserRefreshToken> refreshCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(userRefreshTokenMapper).insert(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
        assertThat(refreshCaptor.getValue().getSessionVersion()).isZero();
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(existingUser());

        assertThatThrownBy(() -> authService.register(new AuthRegisterRequest(
            "admin",
            "admin2@repoguard.dev",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户名已存在");
    }

    @Test
    void registerRejectsWhenSelfRegistrationIsDisabled() {
        authProperties.setRegistrationEnabled(false);

        assertThatThrownBy(() -> authService.register(new AuthRegisterRequest(
            "viewer",
            "viewer@repoguard.dev",
            "Secure123",
            "Secure123"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("公开注册已关闭，请联系管理员开通账号");

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
        verify(userRefreshTokenMapper, never()).insert(any(UserRefreshToken.class));
    }

    @Test
    void loginRejectsWrongPasswordAndRecordsFailure() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Wrong123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        verify(userAccountMapper).recordFailedLogin(
            Mockito.eq(1001L),
            Mockito.eq(20),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginDoesNotOpenTransactionAroundPasswordVerification() throws NoSuchMethodException {
        var transactional = AuthServiceImpl.class
            .getMethod("login", AuthLoginRequest.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(transactional).isNull();
    }

    @Test
    void loginLocksAccountAfterTwentyWrongPasswords() {
        UserAccount user = existingUser();
        user.setFailedLoginCount(19);
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Wrong123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        assertThat(user.getFailedLoginCount()).isEqualTo(20);
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
        verify(userAccountMapper).recordFailedLogin(
            Mockito.eq(1001L),
            Mockito.eq(20),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginRejectsLockedAccountWithoutIssuingToken() {
        UserAccount user = existingUser();
        user.setFailedLoginCount(20);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Secure123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
        Mockito.verify(userRefreshTokenMapper, Mockito.never()).insert(any(UserRefreshToken.class));
    }

    @Test
    void loginReturnsLongerRefreshTokenWhenRememberedAndClearsFailures() {
        authProperties.setAccessTokenTtlSeconds(10);
        authProperties.setRefreshTokenTtlSeconds(20);
        authProperties.setRememberTokenTtlSeconds(30);
        UserAccount user = existingUser();
        user.setSessionVersion(3);
        user.setFailedLoginCount(3);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthResponse response = authService.login(new AuthLoginRequest("admin", "Secure123", true));

        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(10);
        assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(30);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(userAccountMapper).update(isNull(), any(Wrapper.class));
        ArgumentCaptor<UserRefreshToken> refreshCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(userRefreshTokenMapper).insert(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue().getSessionVersion()).isEqualTo(3);
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginAuditUsesProxySuppliedRealIpInsteadOfForwardedChain() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.20");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 10.0.0.9");
        request.addHeader("X-Real-IP", "10.0.0.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        authService.login(new AuthLoginRequest("admin", "Secure123", false));

        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getClientIp()).isEqualTo("10.0.0.7");
    }

    @Test
    void currentUserReturnsActiveUserProfile() {
        UserAccount user = existingUser();
        user.setLastLoginAt(LocalDateTime.parse("2026-06-11T10:00:00"));
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        var profile = authService.currentUser(1001L);

        assertThat(profile.id()).isEqualTo(1001L);
        assertThat(profile.username()).isEqualTo("admin");
        assertThat(profile.email()).isEqualTo("admin@repoguard.dev");
        assertThat(profile.role()).isEqualTo("ADMIN");
        assertThat(profile.status()).isEqualTo("ACTIVE");
        assertThat(profile.lastLoginAt()).isEqualTo(LocalDateTime.parse("2026-06-11T10:00:00"));
    }

    @Test
    void changePasswordRehashesPasswordAndRevokesExistingSessions() {
        UserAccount user = existingUser();
        user.setSessionVersion(4);
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        when(userAccountMapper.updatePasswordAndRotateSession(eq(1001L), anyString(), anyString(), any(LocalDateTime.class)))
            .thenReturn(1);

        authService.changePassword(1001L, new AuthPasswordChangeRequest(
            "Secure123",
            "Safer456",
            "Safer456"
        ));

        ArgumentCaptor<String> newPasswordHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userAccountMapper).updatePasswordAndRotateSession(
            eq(1001L),
            eq(user.getPasswordHash()),
            newPasswordHashCaptor.capture(),
            any(LocalDateTime.class)
        );
        assertThat(passwordHashService.matchesOrDummy("Safer456", newPasswordHashCaptor.getValue())).isTrue();
        assertThat(passwordHashService.matchesOrDummy("Secure123", newPasswordHashCaptor.getValue())).isFalse();
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void changePasswordRejectsConcurrentCredentialUpdateWithoutRevokingSessions() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        when(userAccountMapper.updatePasswordAndRotateSession(eq(1001L), anyString(), anyString(), any(LocalDateTime.class)))
            .thenReturn(0);

        assertThatThrownBy(() -> authService.changePassword(1001L, new AuthPasswordChangeRequest(
            "Secure123",
            "Safer456",
            "Safer456"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Password changed concurrently; sign in again");

        verify(userRefreshTokenMapper, never()).update(isNull(), any(Wrapper.class));
        verify(userLoginAuditMapper, never()).insert(any(UserLoginAudit.class));
    }

    @Test
    void changePasswordRejectsIncorrectCurrentPassword() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> authService.changePassword(1001L, new AuthPasswordChangeRequest(
            "Wrong123",
            "Safer456",
            "Safer456"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Current password is incorrect");

        verify(userAccountMapper, never()).updatePasswordAndRotateSession(
            any(Long.class),
            anyString(),
            anyString(),
            any(LocalDateTime.class)
        );
    }

    @Test
    void currentUserRejectsDisabledUser() {
        UserAccount user = existingUser();
        user.setStatus("DISABLED");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> authService.currentUser(1001L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号不可用，请重新登录");
    }

    @Test
    void refreshRotatesRefreshTokenAndReturnsNewAccessToken() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(existingUser());
        when(userRefreshTokenMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        AuthResponse response = authService.refresh(new AuthRefreshRequest(refreshToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotEqualTo(refreshToken);
        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userRefreshTokenMapper).insert(any(UserRefreshToken.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void refreshRejectsRefreshTokenAlreadyUsedByConcurrentRequest() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(existingUser());
        when(userRefreshTokenMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("登录状态已过期，请重新登录");

        Mockito.verify(userRefreshTokenMapper, Mockito.never()).insert(any(UserRefreshToken.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void refreshDetectsReusedRevokedTokenAndInvalidatesSession() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        storedToken.setStatus("REVOKED");
        storedToken.setSessionVersion(2);
        storedToken.setLastUsedAt(LocalDateTime.now().minusSeconds(6));
        UserAccount user = existingUser();
        user.setSessionVersion(2);
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class);

        assertThat(user.getSessionVersion()).isEqualTo(3);
        assertThat(storedToken.getLastUsedAt()).isNotNull();
        verify(userAccountMapper).updateById(user);
        Mockito.verify(userRefreshTokenMapper, Mockito.times(2)).update(isNull(), any(Wrapper.class));
        Mockito.verify(userRefreshTokenMapper, Mockito.never()).insert(any(UserRefreshToken.class));
        verify(metrics).refreshTokenReuseDetected();
        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getFailureReason()).isEqualTo("refresh token reuse detected");
    }

    @Test
    void refreshTreatsRecentlyRotatedTokenAsConcurrentReplayWithoutInvalidatingSession() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        storedToken.setStatus("REVOKED");
        storedToken.setSessionVersion(2);
        storedToken.setLastUsedAt(LocalDateTime.now().minusSeconds(1));
        UserAccount user = existingUser();
        user.setSessionVersion(2);
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("登录状态已过期，请重新登录");

        assertThat(user.getSessionVersion()).isEqualTo(2);
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userRefreshTokenMapper, never()).insert(any(UserRefreshToken.class));
        verify(metrics).refreshTokenConcurrentReplay();
        verify(metrics, never()).refreshTokenReuseDetected();
        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getFailureReason())
            .isEqualTo("refresh token replay within concurrency grace");
    }

    @Test
    void refreshCommitsReuseInvalidationBeforeThrowingUnauthorized() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        storedToken.setStatus("REVOKED");
        storedToken.setSessionVersion(2);
        UserAccount user = existingUser();
        user.setSessionVersion(2);
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        IdentitySessionLifecycle transactionalSessionLifecycle = new DefaultIdentitySessionLifecycle(
            userAccountMapper,
            userRefreshTokenMapper,
            auditRecorder,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            metrics,
            transactionManager
        );
        AuthServiceImpl transactionalAuthService = new AuthServiceImpl(
            accountLifecycle,
            credentialAuthenticator,
            transactionalSessionLifecycle
        );

        assertThatThrownBy(() -> transactionalAuthService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("登录状态已过期，请重新登录");

        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
        assertThat(transactionManager.lastPropagationBehavior)
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void sessionIssuanceUsesRequiredPropagationForRegistrationComposition() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        IdentitySessionLifecycle transactionalSessionLifecycle = new DefaultIdentitySessionLifecycle(
            userAccountMapper,
            userRefreshTokenMapper,
            auditRecorder,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            metrics,
            transactionManager
        );

        transactionalSessionLifecycle.issue(new IdentityAccount(
            1001L,
            "admin",
            "admin@repoguard.dev",
            "ADMIN",
            0
        ), false);

        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.lastPropagationBehavior)
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void refreshRejectsExpiredRefreshToken() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().minusSeconds(1));
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);

        assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("登录状态已过期，请重新登录");
        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void refreshRejectsTokenIssuedForPreviousSessionVersion() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        storedToken.setSessionVersion(2);
        UserAccount user = existingUser();
        user.setSessionVersion(3);
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(refreshToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("登录状态已过期，请重新登录");

        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userRefreshTokenMapper).updateById(storedToken);
        Mockito.verify(userRefreshTokenMapper, Mockito.never()).insert(any(UserRefreshToken.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void logoutRotatesSessionVersionAndRevokesRefreshTokens() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        UserAccount user = existingUser();
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        authService.logout(new AuthLogoutRequest(refreshToken));

        assertThat(user.getSessionVersion()).isEqualTo(1);
        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void logoutOnlyRevokesRefreshTokenWhenSessionVersionAlreadyChanged() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        storedToken.setSessionVersion(1);
        UserAccount user = existingUser();
        user.setSessionVersion(2);
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(user);

        authService.logout(new AuthLogoutRequest(refreshToken));

        assertThat(user.getSessionVersion()).isEqualTo(2);
        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        verify(userRefreshTokenMapper).updateById(storedToken);
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void resetRefreshTokenVerifiesPasswordAndRevokesExistingTokens() {
        UserAccount user = existingUser();
        user.setSessionVersion(3);
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthResponse response = authService.resetRefreshToken(new AuthRefreshTokenResetRequest("admin", "Secure123", false));

        assertThat(response.refreshToken()).isNotBlank();
        ArgumentCaptor<UserAccount> accountUpdate = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).updateById(accountUpdate.capture());
        assertThat(accountUpdate.getValue().getId()).isEqualTo(1001L);
        assertThat(accountUpdate.getValue().getSessionVersion()).isEqualTo(4);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userRefreshTokenMapper).insert(any(UserRefreshToken.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void constructorRequiresSessionLifecycle() {
        assertThatThrownBy(() -> new AuthServiceImpl(
            accountLifecycle,
            credentialAuthenticator,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sessionLifecycle");
    }

    @Test
    void constructorRequiresAccountLifecycle() {
        assertThatThrownBy(() -> new AuthServiceImpl(
            null,
            credentialAuthenticator,
            sessionLifecycle
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("accountLifecycle");
    }

    @Test
    void constructorRequiresCredentialAuthenticator() {
        assertThatThrownBy(() -> new AuthServiceImpl(
            accountLifecycle,
            null,
            sessionLifecycle
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("credentialAuthenticator");
    }

    private UserRefreshToken activeRefreshToken(String refreshToken, LocalDateTime expiresAt) {
        UserRefreshToken storedToken = new UserRefreshToken();
        storedToken.setId(2001L);
        storedToken.setUserId(1001L);
        storedToken.setTokenHash(authTokenService.hashRefreshToken(refreshToken));
        storedToken.setSessionVersion(0);
        storedToken.setStatus("ACTIVE");
        storedToken.setExpiresAt(expiresAt);
        return storedToken;
    }

    private UserAccount existingUser() {
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
