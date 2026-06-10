package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final PasswordHashService passwordHashService = new PasswordHashService();
    private final AuthProperties authProperties = new AuthProperties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final AuthServiceImpl authService = new AuthServiceImpl(userAccountMapper, passwordHashService, authTokenService);

    @Test
    void registerStoresBCryptHashAndReturnsToken() {
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

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).insert(captor.capture());
        UserAccount saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("admin@repoguard.dev");
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).doesNotContain("Secure123");
        assertThat(response.token()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("admin");
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
    void loginReturnsLongerTokenWhenRemembered() {
        authProperties.setTokenTtlSeconds(10);
        authProperties.setRememberTokenTtlSeconds(20);
        UserAccount user = existingUser();
        user.setPasswordHash(passwordHashService.hash("Secure123"));
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthResponse response = authService.login(new AuthLoginRequest("admin", "Secure123", true));

        assertThat(response.expiresInSeconds()).isEqualTo(20);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(userAccountMapper).updateById(user);
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
