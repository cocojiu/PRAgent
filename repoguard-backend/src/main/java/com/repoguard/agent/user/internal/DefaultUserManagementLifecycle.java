package com.repoguard.agent.user.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.credential.PasswordHasher;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.identity.IdentitySessionInvalidator;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserOperationAuditMapper;
import com.repoguard.agent.user.UserManagementLifecycle;
import com.repoguard.agent.user.UserManagementLifecycle.AuditContext;
import com.repoguard.agent.user.UserManagementLifecycle.CreateCommand;
import com.repoguard.agent.user.UserManagementLifecycle.ManagedUser;
import com.repoguard.agent.user.UserManagementLifecycle.OperationAudit;
import com.repoguard.agent.user.UserManagementLifecycle.PageRequest;
import com.repoguard.agent.user.UserManagementLifecycle.PageResult;
import com.repoguard.agent.user.UserManagementLifecycle.RoleChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.StatusChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.UserSearch;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class DefaultUserManagementLifecycle implements UserManagementLifecycle {

    private static final String ACTION_USER_CREATE = "USER_CREATE";
    private static final String ACTION_ROLE_UPDATE = "ROLE_UPDATE";
    private static final String ACTION_STATUS_UPDATE = "STATUS_UPDATE";
    private static final String ACTIVE_ADMIN_GUARD = "active_admin";

    private final UserAccountMapper userAccountMapper;
    private final UserOperationAuditMapper userOperationAuditMapper;
    private final PasswordHasher passwordHasher;
    private final IdentitySessionInvalidator sessionInvalidator;
    private final UserManagementViewMapper viewMapper = new UserManagementViewMapper();
    private final UserRoleStatusPolicy roleStatusPolicy = new UserRoleStatusPolicy();
    private final UserOperationAuditRecorder auditRecorder;

    public DefaultUserManagementLifecycle(
        UserAccountMapper userAccountMapper,
        UserOperationAuditMapper userOperationAuditMapper,
        PasswordHasher passwordHasher,
        IdentitySessionInvalidator sessionInvalidator
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.userOperationAuditMapper =
            Objects.requireNonNull(userOperationAuditMapper, "userOperationAuditMapper must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator, "sessionInvalidator must not be null");
        this.auditRecorder = new UserOperationAuditRecorder(userAccountMapper, userOperationAuditMapper);
    }

    @Override
    public PageResult<ManagedUser> listUsers(UserSearch search) {
        Objects.requireNonNull(search, "search must not be null");
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<UserAccount>();
        if (StringUtils.hasText(search.role())) {
            wrapper.eq(UserAccount::getRole, search.role().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(search.status())) {
            wrapper.eq(UserAccount::getStatus, search.status().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(search.keyword())) {
            String query = search.keyword().trim();
            wrapper.and(condition -> condition
                .like(UserAccount::getUsername, query)
                .or()
                .like(UserAccount::getEmail, query)
            );
        }
        Page<UserAccount> result = userAccountMapper.selectPage(
            Page.of(search.page(), search.pageSize()),
            wrapper
                .orderByDesc(UserAccount::getCreatedAt)
                .orderByAsc(UserAccount::getId)
        );
        return new PageResult<>(result.getRecords().stream()
            .map(viewMapper::toManagedUser)
            .toList(), result.getTotal());
    }

    @Override
    public PageResult<OperationAudit> listOperationAudits(PageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        Page<UserOperationAudit> result = userOperationAuditMapper.selectPage(
            Page.of(pageRequest.page(), pageRequest.pageSize()),
            new LambdaQueryWrapper<UserOperationAudit>()
                .orderByDesc(UserOperationAudit::getCreatedAt)
                .orderByDesc(UserOperationAudit::getId)
        );
        return new PageResult<>(result.getRecords().stream()
            .map(viewMapper::toOperationAudit)
            .toList(), result.getTotal());
    }

    @Override
    @Transactional
    public ManagedUser createUser(AuditContext auditContext, CreateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!command.password().equals(command.confirmPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
        }
        if (!isStrongEnough(command.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 8 位，且必须同时包含字母和数字");
        }
        String username = command.username().trim();
        String email = command.email().trim().toLowerCase(Locale.ROOT);
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
        user.setPasswordHash(passwordHasher.hash(command.password()));
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
        return viewMapper.toManagedUser(user);
    }

    @Override
    @Transactional
    public ManagedUser updateRole(AuditContext auditContext, RoleChangeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String normalizedRole = roleStatusPolicy.normalizeRole(command.role());
        lockActiveAdminInvariant(roleStatusPolicy.isViewerRole(normalizedRole));
        UserAccount user = requireUser(command.userId());
        String beforeRole = user.getRole();
        if (roleStatusPolicy.isAdmin(user) && roleStatusPolicy.isViewerRole(normalizedRole)) {
            ensureAnotherActiveAdmin(command.userId());
        }
        user.setRole(normalizedRole);
        LocalDateTime now = LocalDateTime.now();
        user.setUpdatedAt(now);
        userAccountMapper.updateById(user);
        sessionInvalidator.invalidateAccountSessions(user.getId(), SessionInvalidationMode.ALL_SESSIONS, now);
        auditRecorder.record(auditContext, user, ACTION_ROLE_UPDATE, beforeRole, normalizedRole);
        return viewMapper.toManagedUser(user);
    }

    @Override
    @Transactional
    public ManagedUser updateStatus(AuditContext auditContext, StatusChangeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String normalizedStatus = roleStatusPolicy.normalizeStatus(command.status());
        lockActiveAdminInvariant(roleStatusPolicy.isDisabledStatus(normalizedStatus));
        UserAccount user = requireUser(command.userId());
        String beforeStatus = user.getStatus();
        Long operatorId = auditContext == null ? null : auditContext.operatorId();
        if (operatorId != null
            && operatorId.equals(command.userId())
            && roleStatusPolicy.isDisabledStatus(normalizedStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable your own account");
        }
        if (roleStatusPolicy.isAdmin(user) && roleStatusPolicy.isDisabledStatus(normalizedStatus)) {
            ensureAnotherActiveAdmin(command.userId());
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
        return viewMapper.toManagedUser(user);
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
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
            .eq(UserAccount::getUsername, username));
    }

    private UserAccount findByEmail(String email) {
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
            .eq(UserAccount::getEmail, email));
    }

    private boolean isStrongEnough(String password) {
        return password.chars().anyMatch(Character::isLetter)
            && password.chars().anyMatch(Character::isDigit);
    }
}
