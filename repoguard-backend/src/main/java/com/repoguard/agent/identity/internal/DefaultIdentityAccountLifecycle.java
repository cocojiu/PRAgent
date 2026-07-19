package com.repoguard.agent.identity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityAccountLifecycle;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.IdentitySessionTokens;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.PasswordHashService;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public final class DefaultIdentityAccountLifecycle implements IdentityAccountLifecycle {

    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";

    private final UserAccountMapper userAccountMapper;
    private final IdentityAuditRecorder auditRecorder;
    private final PasswordHashService passwordHashService;
    private final IdentitySessionLifecycle sessionLifecycle;
    private final AuthProperties authProperties;
    private final TransactionTemplate accountWriteTransaction;

    @Autowired
    public DefaultIdentityAccountLifecycle(
        UserAccountMapper userAccountMapper,
        IdentityAuditRecorder auditRecorder,
        PasswordHashService passwordHashService,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties,
        PlatformTransactionManager transactionManager
    ) {
        this(
            userAccountMapper,
            auditRecorder,
            passwordHashService,
            sessionLifecycle,
            authProperties,
            buildWriteTransaction(transactionManager)
        );
    }

    public DefaultIdentityAccountLifecycle(
        UserAccountMapper userAccountMapper,
        IdentityAuditRecorder auditRecorder,
        PasswordHashService passwordHashService,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties
    ) {
        this(
            userAccountMapper,
            auditRecorder,
            passwordHashService,
            sessionLifecycle,
            authProperties,
            (TransactionTemplate) null
        );
    }

    private DefaultIdentityAccountLifecycle(
        UserAccountMapper userAccountMapper,
        IdentityAuditRecorder auditRecorder,
        PasswordHashService passwordHashService,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties,
        TransactionTemplate accountWriteTransaction
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
        this.passwordHashService = Objects.requireNonNull(passwordHashService, "passwordHashService must not be null");
        this.sessionLifecycle = Objects.requireNonNull(sessionLifecycle, "sessionLifecycle must not be null");
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
        this.accountWriteTransaction = accountWriteTransaction;
    }

    @Override
    public IdentitySessionTokens register(RegistrationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!authProperties.isRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "公开注册已关闭，请联系管理员开通账号");
        }
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

        String passwordHash = passwordHashService.hash(command.password());
        LocalDateTime now = LocalDateTime.now();
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(ROLE_VIEWER);
        user.setStatus(STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setSessionVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastLoginAt(now);
        return inWriteTransaction(() -> {
            try {
                userAccountMapper.insert(user);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或邮箱已存在");
            }
            recordAudit(user.getId(), user.getUsername(), "REGISTER", AUDIT_SUCCESS, null);
            return sessionLifecycle.issue(toIdentityAccount(user), false);
        });
    }

    @Override
    public Profile currentProfile(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不可用，请重新登录");
        }
        return new Profile(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getStatus(),
            user.getLastLoginAt()
        );
    }

    @Override
    public void changePassword(Long userId, PasswordChangeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        UserAccount user = userAccountMapper.selectById(userId);
        boolean currentPasswordMatches = passwordHashService.matchesOrDummy(
            command.currentPassword(),
            user == null ? null : user.getPasswordHash()
        );
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus()) || !currentPasswordMatches) {
            recordAuditInWriteTransaction(
                userId,
                user == null ? null : user.getUsername(),
                "PASSWORD_CHANGE",
                "bad credentials"
            );
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Current password is incorrect");
        }
        if (!command.newPassword().equals(command.confirmPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password and confirmation do not match");
        }
        if (!isStrongEnough(command.newPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password must contain both letters and numbers");
        }
        if (passwordHashService.matchesOrDummy(command.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password must differ from the current password");
        }

        String currentPasswordHash = user.getPasswordHash();
        String newPasswordHash = passwordHashService.hash(command.newPassword());
        inWriteTransaction(() -> {
            LocalDateTime now = LocalDateTime.now();
            int updated = userAccountMapper.updatePasswordAndRotateSession(
                user.getId(),
                currentPasswordHash,
                newPasswordHash,
                now
            );
            if (updated != 1) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Password changed concurrently; sign in again");
            }
            sessionLifecycle.invalidateAccountSessions(
                user.getId(),
                SessionInvalidationMode.REFRESH_TOKENS_ONLY,
                now
            );
            recordAudit(user.getId(), user.getUsername(), "PASSWORD_CHANGE", AUDIT_SUCCESS, null);
            return null;
        });
    }

    private IdentityAccount toIdentityAccount(UserAccount user) {
        return new IdentityAccount(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getSessionVersion() == null ? 0 : user.getSessionVersion()
        );
    }

    private void recordAudit(Long userId, String account, String eventType, String result, String failureReason) {
        auditRecorder.record(userId, account, eventType, result, failureReason);
    }

    private void recordAuditInWriteTransaction(
        Long userId,
        String account,
        String eventType,
        String failureReason
    ) {
        inWriteTransaction(() -> {
            recordAudit(userId, account, eventType, AUDIT_FAILURE, failureReason);
            return null;
        });
    }

    private <T> T inWriteTransaction(Supplier<T> operation) {
        if (accountWriteTransaction == null) {
            return operation.get();
        }
        return accountWriteTransaction.execute(status -> operation.get());
    }

    private static TransactionTemplate buildWriteTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private UserAccount findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userAccountMapper.selectOne(
            new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username)
        );
    }

    private UserAccount findByEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        return userAccountMapper.selectOne(
            new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getEmail, email)
        );
    }

    private boolean isStrongEnough(String password) {
        return password.chars().anyMatch(Character::isLetter)
            && password.chars().anyMatch(Character::isDigit);
    }
}
