package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final AuthProperties authProperties = new AuthProperties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final AuthServiceImpl authService = new AuthServiceImpl(
        userAccountMapper,
        userRefreshTokenMapper,
        passwordHashService,
        authTokenService
    );

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
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("admin");

        ArgumentCaptor<UserRefreshToken> refreshCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(userRefreshTokenMapper).insert(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
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
    void loginRejectsWrongPassword() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "Wrong123", false)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号或密码错误");
    }

    @Test
    void loginReturnsLongerRefreshTokenWhenRemembered() {
        authProperties.setAccessTokenTtlSeconds(10);
        authProperties.setRefreshTokenTtlSeconds(20);
        authProperties.setRememberTokenTtlSeconds(30);
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthResponse response = authService.login(new AuthLoginRequest("admin", "Secure123", true));

        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(10);
        assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(30);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(userAccountMapper).updateById(user);
    }

    @Test
    void refreshRotatesRefreshTokenAndReturnsNewAccessToken() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);
        when(userAccountMapper.selectById(1001L)).thenReturn(existingUser());

        AuthResponse response = authService.refresh(new AuthRefreshRequest(refreshToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotEqualTo(refreshToken);
        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userRefreshTokenMapper).updateById(storedToken);
        verify(userRefreshTokenMapper).insert(any(UserRefreshToken.class));
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
    }

    @Test
    void logoutRevokesRefreshToken() {
        String refreshToken = "refresh-token";
        UserRefreshToken storedToken = activeRefreshToken(refreshToken, LocalDateTime.now().plusHours(1));
        when(userRefreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(storedToken);

        authService.logout(new AuthLogoutRequest(refreshToken));

        assertThat(storedToken.getStatus()).isEqualTo("REVOKED");
        verify(userRefreshTokenMapper).updateById(storedToken);
    }

    @Test
    void resetRefreshTokenVerifiesPasswordAndRevokesExistingTokens() {
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthResponse response = authService.resetRefreshToken(new AuthRefreshTokenResetRequest("admin", "Secure123", false));

        assertThat(response.refreshToken()).isNotBlank();
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
        verify(userRefreshTokenMapper).insert(any(UserRefreshToken.class));
    }

    private UserRefreshToken activeRefreshToken(String refreshToken, LocalDateTime expiresAt) {
        UserRefreshToken storedToken = new UserRefreshToken();
        storedToken.setId(2001L);
        storedToken.setUserId(1001L);
        storedToken.setTokenHash(authTokenService.hashRefreshToken(refreshToken));
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
        return user;
    }
}
