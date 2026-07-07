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
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.user.UserAccountSessionInvalidator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final UserLoginAuditMapper userLoginAuditMapper = Mockito.mock(UserLoginAuditMapper.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final AuthProperties authProperties = new AuthProperties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final UserAccountSessionInvalidator sessionInvalidator =
        new UserAccountSessionInvalidator(userRefreshTokenMapper);
    private final AuthServiceImpl authService = new AuthServiceImpl(
        userAccountMapper,
        userRefreshTokenMapper,
        userLoginAuditMapper,
        passwordHashService,
        authProperties,
        authTokenService,
        sessionInvalidator
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
        verify(userAccountMapper).update(isNull(), any(Wrapper.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginDoesNotRollBackFailureCounterWhenBusinessExceptionIsThrown() throws NoSuchMethodException {
        Transactional transactional = AuthServiceImpl.class
            .getMethod("login", AuthLoginRequest.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(BusinessException.class);
    }

    @Test
    void loginLocksAccountAfterFiveWrongPasswords() {
        UserAccount user = existingUser();
        user.setFailedLoginCount(4);
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Wrong123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");

        assertThat(user.getFailedLoginCount()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
        verify(userAccountMapper).update(isNull(), any(Wrapper.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginRejectsLockedAccountWithoutIssuingToken() {
        UserAccount user = existingUser();
        user.setFailedLoginCount(5);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Secure123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号已暂时锁定，请 15 分钟后再试");

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
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(userAccountMapper).update(isNull(), any(Wrapper.class));
        ArgumentCaptor<UserRefreshToken> refreshCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(userRefreshTokenMapper).insert(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue().getSessionVersion()).isEqualTo(3);
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void loginAuditIgnoresSpoofedForwardedHeaders() {
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
        assertThat(auditCaptor.getValue().getClientIp()).isEqualTo("192.0.2.20");
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
        assertThat(user.getSessionVersion()).isEqualTo(4);
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userRefreshTokenMapper).insert(any(UserRefreshToken.class));
        verify(userLoginAuditMapper).insert(any(UserLoginAudit.class));
    }

    @Test
    void constructorRequiresSessionInvalidator() {
        assertThatThrownBy(() -> new AuthServiceImpl(
            userAccountMapper,
            userRefreshTokenMapper,
            userLoginAuditMapper,
            passwordHashService,
            authProperties,
            authTokenService,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sessionInvalidator");
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
}
