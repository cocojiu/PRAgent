package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.service.UserManagementService;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String ACTION_USER_CREATE = "USER_CREATE";
    private static final String ACTION_ROLE_UPDATE = "ROLE_UPDATE";
    private static final String ACTION_STATUS_UPDATE = "STATUS_UPDATE";
    private static final int AUDIT_LIMIT = 50;

    private final UserAccountMapper userAccountMapper;
    private final UserRefreshTokenMapper userRefreshTokenMapper;
    private final UserOperationAuditMapper userOperationAuditMapper;
    private final PasswordHashService passwordHashService;

    public UserManagementServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserOperationAuditMapper userOperationAuditMapper,
        PasswordHashService passwordHashService
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRefreshTokenMapper = userRefreshTokenMapper;
        this.userOperationAuditMapper = userOperationAuditMapper;
        this.passwordHashService = passwordHashService;
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
    public List<UserOperationAuditDto> listOperationAudits() {
        return userOperationAuditMapper.selectList(new LambdaQueryWrapper<UserOperationAudit>()
                .orderByDesc(UserOperationAudit::getCreatedAt)
                .orderByDesc(UserOperationAudit::getId)
                .last("LIMIT " + AUDIT_LIMIT))
            .stream()
            .map(this::toAuditDto)
            .toList();
    }

    @Override
    @Transactional
    public UserManagementItemDto createUser(UserOperationAuditContext auditContext, UserCreateRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
        }
        if (!isStrongEnough(request.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 8 位，且必须同时包含字母和数字");
        }
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        if (findByEmail(email) != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHashService.hash(request.password()));
        user.setRole(ROLE_VIEWER);
        user.setStatus(STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setSessionVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userAccountMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或邮箱已存在");
        }
        recordAudit(auditContext, user, ACTION_USER_CREATE, null, ROLE_VIEWER);
        return toDto(user);
    }

    @Override
    @Transactional
    public UserManagementItemDto updateRole(UserOperationAuditContext auditContext, Long userId, String role) {
        String normalizedRole = normalizeRole(role);
        UserAccount user = requireUser(userId);
        String beforeRole = user.getRole();
        if (ROLE_ADMIN.equals(user.getRole()) && ROLE_VIEWER.equals(normalizedRole)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setRole(normalizedRole);
        rotateSessionVersion(user, LocalDateTime.now());
        userAccountMapper.updateById(user);
        revokeActiveRefreshTokens(user.getId());
        recordAudit(auditContext, user, ACTION_ROLE_UPDATE, beforeRole, normalizedRole);
        return toDto(user);
    }

    @Override
    @Transactional
    public UserManagementItemDto updateStatus(UserOperationAuditContext auditContext, Long userId, String status) {
        String normalizedStatus = normalizeStatus(status);
        UserAccount user = requireUser(userId);
        String beforeStatus = user.getStatus();
        Long operatorId = auditContext == null ? null : auditContext.operatorId();
        if (operatorId != null && operatorId.equals(userId) && STATUS_DISABLED.equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable your own account");
        }
        if (ROLE_ADMIN.equals(user.getRole()) && STATUS_DISABLED.equals(normalizedStatus)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setStatus(normalizedStatus);
        LocalDateTime now = LocalDateTime.now();
        rotateSessionVersion(user, now);
        if (STATUS_ACTIVE.equals(normalizedStatus)) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }
        userAccountMapper.updateById(user);
        if (STATUS_DISABLED.equals(normalizedStatus)) {
            revokeActiveRefreshTokens(user.getId());
        }
        recordAudit(auditContext, user, ACTION_STATUS_UPDATE, beforeStatus, normalizedStatus);
        return toDto(user);
    }

    private void recordAudit(
        UserOperationAuditContext auditContext,
        UserAccount targetUser,
        String action,
        String beforeValue,
        String afterValue
    ) {
        UserOperationAudit audit = new UserOperationAudit();
        Long operatorId = auditContext == null ? null : auditContext.operatorId();
        UserAccount operator = operatorId == null ? null : userAccountMapper.selectById(operatorId);
        audit.setOperatorUserId(operatorId);
        audit.setOperatorUsername(operator == null ? null : operator.getUsername());
        audit.setTargetUserId(targetUser.getId());
        audit.setTargetUsername(targetUser.getUsername());
        audit.setAction(action);
        audit.setBeforeValue(beforeValue);
        audit.setAfterValue(afterValue);
        audit.setClientIp(auditContext == null ? null : truncate(auditContext.clientIp(), 64));
        audit.setUserAgent(auditContext == null ? null : truncate(auditContext.userAgent(), 512));
        audit.setCreatedAt(LocalDateTime.now());
        userOperationAuditMapper.insert(audit);
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

    private void rotateSessionVersion(UserAccount user, LocalDateTime now) {
        user.setSessionVersion((user.getSessionVersion() == null ? 0 : user.getSessionVersion()) + 1);
        user.setUpdatedAt(now);
    }

    private UserAccount findByUsername(String username) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
    }

    private UserAccount findByEmail(String email) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getEmail, email));
    }

    private boolean isStrongEnough(String password) {
        return password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
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

    private UserOperationAuditDto toAuditDto(UserOperationAudit audit) {
        return new UserOperationAuditDto(
            audit.getId(),
            audit.getOperatorUserId(),
            audit.getOperatorUsername(),
            audit.getTargetUserId(),
            audit.getTargetUsername(),
            audit.getAction(),
            audit.getBeforeValue(),
            audit.getAfterValue(),
            audit.getClientIp(),
            audit.getUserAgent(),
            audit.getCreatedAt()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
