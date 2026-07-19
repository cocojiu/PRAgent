package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.identity.IdentitySessionInvalidator;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.service.UserManagementService;
import com.repoguard.agent.user.UserManagementDisplayMapper;
import com.repoguard.agent.user.UserOperationAuditRecorder;
import com.repoguard.agent.user.UserRoleStatusPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final String ACTION_USER_CREATE = "USER_CREATE";
    private static final String ACTION_ROLE_UPDATE = "ROLE_UPDATE";
    private static final String ACTION_STATUS_UPDATE = "STATUS_UPDATE";
    private static final String ACTIVE_ADMIN_GUARD = "active_admin";
    private final UserAccountMapper userAccountMapper;
    private final UserOperationAuditMapper userOperationAuditMapper;
    private final PasswordHashService passwordHashService;
    private final UserManagementDisplayMapper displayMapper;
    private final UserOperationAuditRecorder auditRecorder;
    private final IdentitySessionInvalidator sessionInvalidator;
    private final UserRoleStatusPolicy roleStatusPolicy;

    public UserManagementServiceImpl(
        UserAccountMapper userAccountMapper,
        UserOperationAuditMapper userOperationAuditMapper,
        PasswordHashService passwordHashService,
        UserManagementDisplayMapper displayMapper,
        UserOperationAuditRecorder auditRecorder,
        IdentitySessionInvalidator sessionInvalidator,
        UserRoleStatusPolicy roleStatusPolicy
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.userOperationAuditMapper =
            Objects.requireNonNull(userOperationAuditMapper, "userOperationAuditMapper must not be null");
        this.passwordHashService = Objects.requireNonNull(passwordHashService, "passwordHashService must not be null");
        this.displayMapper = Objects.requireNonNull(displayMapper, "displayMapper must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator, "sessionInvalidator must not be null");
        this.roleStatusPolicy = Objects.requireNonNull(roleStatusPolicy, "roleStatusPolicy must not be null");
    }

    @Override
    public PageResponse<UserManagementItemDto> listUsers(int page, int pageSize, String role, String status, String keyword) {
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<UserAccount>();
        if (StringUtils.hasText(role)) {
            wrapper.eq(UserAccount::getRole, role.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(UserAccount::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(keyword)) {
            String query = keyword.trim();
            wrapper.and(condition -> condition
                .like(UserAccount::getUsername, query)
                .or()
                .like(UserAccount::getEmail, query)
            );
        }
        Page<UserAccount> result = userAccountMapper.selectPage(
            Page.of(page, pageSize),
            wrapper
            .orderByDesc(UserAccount::getCreatedAt)
            .orderByAsc(UserAccount::getId)
        );
        return new PageResponse<>(result.getRecords().stream()
            .map(displayMapper::toUserItem)
            .toList(), result.getTotal());
    }

    @Override
    public PageResponse<UserOperationAuditDto> listOperationAudits(int page, int pageSize) {
        Page<UserOperationAudit> result = userOperationAuditMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<UserOperationAudit>()
            .orderByDesc(UserOperationAudit::getCreatedAt)
            .orderByDesc(UserOperationAudit::getId)
        );
        return new PageResponse<>(result.getRecords().stream()
            .map(displayMapper::toAuditItem)
            .toList(), result.getTotal());
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
        user.setRole(roleStatusPolicy.viewerRole());
        user.setStatus(roleStatusPolicy.activeStatus());
        user.setFailedLoginCount(0);
        user.setSessionVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userAccountMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或邮箱已存在");
        }
        auditRecorder.record(auditContext, user, ACTION_USER_CREATE, null, roleStatusPolicy.viewerRole());
        return displayMapper.toUserItem(user);
    }

    @Override
    @Transactional
    public UserManagementItemDto updateRole(UserOperationAuditContext auditContext, Long userId, String role) {
        String normalizedRole = roleStatusPolicy.normalizeRole(role);
        lockActiveAdminInvariant(roleStatusPolicy.isViewerRole(normalizedRole));
        UserAccount user = requireUser(userId);
        String beforeRole = user.getRole();
        if (roleStatusPolicy.isAdmin(user) && roleStatusPolicy.isViewerRole(normalizedRole)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setRole(normalizedRole);
        LocalDateTime now = LocalDateTime.now();
        user.setUpdatedAt(now);
        userAccountMapper.updateById(user);
        sessionInvalidator.invalidateAccountSessions(user.getId(), SessionInvalidationMode.ALL_SESSIONS, now);
        auditRecorder.record(auditContext, user, ACTION_ROLE_UPDATE, beforeRole, normalizedRole);
        return displayMapper.toUserItem(user);
    }

    @Override
    @Transactional
    public UserManagementItemDto updateStatus(UserOperationAuditContext auditContext, Long userId, String status) {
        String normalizedStatus = roleStatusPolicy.normalizeStatus(status);
        lockActiveAdminInvariant(roleStatusPolicy.isDisabledStatus(normalizedStatus));
        UserAccount user = requireUser(userId);
        String beforeStatus = user.getStatus();
        Long operatorId = auditContext == null ? null : auditContext.operatorId();
        if (operatorId != null && operatorId.equals(userId) && roleStatusPolicy.isDisabledStatus(normalizedStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable your own account");
        }
        if (roleStatusPolicy.isAdmin(user) && roleStatusPolicy.isDisabledStatus(normalizedStatus)) {
            ensureAnotherActiveAdmin(userId);
        }
        user.setStatus(normalizedStatus);
        LocalDateTime now = LocalDateTime.now();
        user.setUpdatedAt(now);
        if (roleStatusPolicy.isActiveStatus(normalizedStatus)) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }
        userAccountMapper.updateById(user);
        SessionInvalidationMode invalidationMode = roleStatusPolicy.isDisabledStatus(normalizedStatus)
            ? SessionInvalidationMode.ALL_SESSIONS
            : SessionInvalidationMode.SESSION_VERSION_ONLY;
        sessionInvalidator.invalidateAccountSessions(user.getId(), invalidationMode, now);
        auditRecorder.record(auditContext, user, ACTION_STATUS_UPDATE, beforeStatus, normalizedStatus);
        return displayMapper.toUserItem(user);
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
            .eq(UserAccount::getRole, roleStatusPolicy.adminRole())
            .eq(UserAccount::getStatus, roleStatusPolicy.activeStatus())
            .ne(UserAccount::getId, userId));
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "At least one active administrator is required");
        }
    }

    private void lockActiveAdminInvariant(boolean required) {
        if (!required) {
            return;
        }
        String lockedGuard = userAccountMapper.lockActiveAdminInvariant();
        if (!ACTIVE_ADMIN_GUARD.equals(lockedGuard)) {
            throw new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "Active administrator guard is unavailable"
            );
        }
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

}
