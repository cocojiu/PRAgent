package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserManagementServiceImplTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final UserManagementServiceImpl userManagementService = new UserManagementServiceImpl(
        userAccountMapper,
        userRefreshTokenMapper
    );

    @Test
    void listUsersReturnsUserManagementItems() {
        when(userAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(user(1001L, "admin", "ADMIN", "ACTIVE")));

        var users = userManagementService.listUsers();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).username()).isEqualTo("admin");
        assertThat(users.get(0).role()).isEqualTo("ADMIN");
    }

    @Test
    void updateRoleDemotesAdminWhenAnotherActiveAdminExists() {
        UserAccount user = user(1001L, "admin", "ADMIN", "ACTIVE");
        when(userAccountMapper.selectById(1001L)).thenReturn(user);
        when(userAccountMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var updated = userManagementService.updateRole(1002L, 1001L, "VIEWER");

        assertThat(updated.role()).isEqualTo("VIEWER");
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void updateRoleRejectsDemotingLastActiveAdmin() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));
        when(userAccountMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> userManagementService.updateRole(1002L, 1001L, "VIEWER"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("At least one active administrator is required");
    }

    @Test
    void updateStatusRejectsDisablingCurrentUser() {
        when(userAccountMapper.selectById(1001L)).thenReturn(user(1001L, "admin", "ADMIN", "ACTIVE"));

        assertThatThrownBy(() -> userManagementService.updateStatus(1001L, 1001L, "DISABLED"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot disable your own account");
    }

    @Test
    void updateStatusDisablesUserAndRevokesRefreshTokens() {
        UserAccount user = user(1003L, "viewer", "VIEWER", "ACTIVE");
        when(userAccountMapper.selectById(1003L)).thenReturn(user);

        var updated = userManagementService.updateStatus(1001L, 1003L, "DISABLED");

        assertThat(updated.status()).isEqualTo("DISABLED");
        verify(userAccountMapper).updateById(user);
        verify(userRefreshTokenMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void updateStatusReactivatesUserAndClearsLockState() {
        UserAccount user = user(1003L, "viewer", "VIEWER", "DISABLED");
        user.setFailedLoginCount(5);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(userAccountMapper.selectById(1003L)).thenReturn(user);

        var updated = userManagementService.updateStatus(1001L, 1003L, "ACTIVE");

        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.failedLoginCount()).isZero();
        assertThat(updated.lockedUntil()).isNull();
        verify(userAccountMapper).updateById(user);
        Mockito.verify(userRefreshTokenMapper, Mockito.never()).update(isNull(), any(Wrapper.class));
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
