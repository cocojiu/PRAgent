package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.service.UserManagementService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_REVOKED = "REVOKED";

    private final UserAccountMapper userAccountMapper;
    private final UserRefreshTokenMapper userRefreshTokenMapper;

    public UserManagementServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRefreshTokenMapper = userRefreshTokenMapper;
    }

    @Override
    public List<UserManagementItemDto> listUsers() {
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                .orderByDesc(UserAccount::getCreatedAt)
                .orderByAsc(UserAccount::getId))
            .stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional
    public UserManagementItemDto updateRole(Long operatorId, Long userId, String role) {
        String normalizedRole = normalizeRole(role);
        UserAccount user = requireUser(userId);
        if (ROLE_ADMIN.equals(user.getRole()) && ROLE_VIEWER.equals(normalizedRole)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setRole(normalizedRole);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(user);
        revokeActiveRefreshTokens(user.getId());
        return toDto(user);
    }

    @Override
    @Transactional
    public UserManagementItemDto updateStatus(Long operatorId, Long userId, String status) {
        String normalizedStatus = normalizeStatus(status);
        UserAccount user = requireUser(userId);
        if (operatorId != null && operatorId.equals(userId) && STATUS_DISABLED.equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable your own account");
        }
        if (ROLE_ADMIN.equals(user.getRole()) && STATUS_DISABLED.equals(normalizedStatus)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setStatus(normalizedStatus);
        user.setUpdatedAt(LocalDateTime.now());
        if (STATUS_ACTIVE.equals(normalizedStatus)) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }
        userAccountMapper.updateById(user);
        if (STATUS_DISABLED.equals(normalizedStatus)) {
            revokeActiveRefreshTokens(user.getId());
        }
        return toDto(user);
    }

    private UserAccount requireUser(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "User does not exist");
        }
        return user;
    }

    private void ensureAnotherActiveAdmin(Long userId) {
        Long count = userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
            .eq(UserAccount::getRole, ROLE_ADMIN)
            .eq(UserAccount::getStatus, STATUS_ACTIVE)
            .ne(UserAccount::getId, userId));
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "At least one active administrator is required");
        }
    }

    private void revokeActiveRefreshTokens(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("user_id", userId)
            .eq("status", STATUS_ACTIVE)
            .set("status", STATUS_REVOKED)
            .set("revoked_at", now)
            .set("updated_at", now));
    }

    private String normalizeRole(String role) {
        if (ROLE_ADMIN.equals(role) || ROLE_VIEWER.equals(role)) {
            return role;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user role");
    }

    private String normalizeStatus(String status) {
        if (STATUS_ACTIVE.equals(status) || STATUS_DISABLED.equals(status)) {
            return status;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported user status");
    }

    private UserManagementItemDto toDto(UserAccount user) {
        return new UserManagementItemDto(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getStatus(),
            user.getFailedLoginCount(),
            user.getLockedUntil(),
            user.getLastLoginAt(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }
}
