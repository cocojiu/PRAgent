package com.repoguard.agent.identity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.credential.PasswordHasher;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.mapper.UserAccountMapper;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public final class DefaultIdentityCredentialAuthenticator implements IdentityCredentialAuthenticator {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";
    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 20;
    private static final long ACCOUNT_LOCK_MINUTES = 5;

    private final UserAccountMapper userAccountMapper;
    private final PasswordHasher passwordHasher;
    private final IdentityAuditRecorder auditRecorder;
    private final TransactionTemplate failureWriteTransaction;

    @Autowired
    public DefaultIdentityCredentialAuthenticator(
        UserAccountMapper userAccountMapper,
        PasswordHasher passwordHasher,
        IdentityAuditRecorder auditRecorder,
        PlatformTransactionManager transactionManager
    ) {
        this(
            userAccountMapper,
            passwordHasher,
            auditRecorder,
            buildWriteTransaction(transactionManager)
        );
    }

    public DefaultIdentityCredentialAuthenticator(
        UserAccountMapper userAccountMapper,
        PasswordHasher passwordHasher,
        IdentityAuditRecorder auditRecorder
    ) {
        this(userAccountMapper, passwordHasher, auditRecorder, (TransactionTemplate) null);
    }

    private DefaultIdentityCredentialAuthenticator(
        UserAccountMapper userAccountMapper,
        PasswordHasher passwordHasher,
        IdentityAuditRecorder auditRecorder,
        TransactionTemplate failureWriteTransaction
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
        this.failureWriteTransaction = failureWriteTransaction;
    }

    @Override
    public IdentityAccount authenticate(
        String accountValue,
        String password,
        AuthenticationOperation operation
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        String account = accountValue.trim();
        UserAccount user = account.contains("@")
            ? findByEmail(account.toLowerCase(Locale.ROOT))
            : findByUsername(account);
        boolean passwordMatches = passwordHasher.matchesOrDummy(
            password,
            user == null ? null : user.getPasswordHash()
        );
        LocalDateTime now = LocalDateTime.now();
        if (user != null && isLocked(user, now)) {
            recordFailureInWriteTransaction(user.getId(), account, operation, "account locked");
            throw invalidCredentials();
        }
        if (user == null || !passwordMatches) {
            handleFailedCredentialAttempt(user, account, operation, "bad credentials", now);
            throw invalidCredentials();
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            recordFailureInWriteTransaction(user.getId(), account, operation, "account disabled");
            throw invalidCredentials();
        }
        return toIdentityAccount(user);
    }

    @Override
    public void recordSuccess(
        IdentityAccount user,
        String account,
        AuthenticationOperation operation,
        LocalDateTime occurredAt
    ) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (operation.clearsLoginFailures()) {
            clearLoginFailures(user.id(), occurredAt);
        }
        auditRecorder.record(
            user.id(),
            account,
            operation.auditEventType(),
            AUDIT_SUCCESS,
            null
        );
    }

    private void handleFailedCredentialAttempt(
        UserAccount user,
        String account,
        AuthenticationOperation operation,
        String reason,
        LocalDateTime now
    ) {
        if (user == null) {
            recordFailureInWriteTransaction(null, account, operation, reason);
            return;
        }
        int failedCount = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        LocalDateTime lockedUntil = now.plusMinutes(ACCOUNT_LOCK_MINUTES);
        inFailureWriteTransaction(() -> {
            userAccountMapper.recordFailedLogin(
                user.getId(),
                MAX_FAILED_LOGIN_ATTEMPTS,
                lockedUntil,
                now
            );
            auditRecorder.record(
                user.getId(),
                account,
                operation.auditEventType(),
                AUDIT_FAILURE,
                reason
            );
            return null;
        });
        user.setFailedLoginCount(failedCount);
        user.setLockedUntil(failedCount >= MAX_FAILED_LOGIN_ATTEMPTS ? lockedUntil : null);
        user.setUpdatedAt(now);
    }

    private void clearLoginFailures(Long userId, LocalDateTime now) {
        UpdateWrapper<UserAccount> update = new UpdateWrapper<UserAccount>()
            .eq("id", userId)
            .set("last_login_at", now)
            .set("failed_login_count", 0)
            .set("locked_until", null)
            .set("updated_at", now);
        userAccountMapper.update(null, update);
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

    private void recordFailureInWriteTransaction(
        Long userId,
        String account,
        AuthenticationOperation operation,
        String failureReason
    ) {
        inFailureWriteTransaction(() -> {
            auditRecorder.record(
                userId,
                account,
                operation.auditEventType(),
                AUDIT_FAILURE,
                failureReason
            );
            return null;
        });
    }

    private <T> T inFailureWriteTransaction(Supplier<T> operation) {
        if (failureWriteTransaction == null) {
            return operation.get();
        }
        return failureWriteTransaction.execute(status -> operation.get());
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

    private boolean isLocked(UserAccount user, LocalDateTime now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
    }

    private static TransactionTemplate buildWriteTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
