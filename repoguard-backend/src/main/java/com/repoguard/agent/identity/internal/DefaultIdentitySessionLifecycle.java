package com.repoguard.agent.identity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator.AuthenticationOperation;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.IdentitySessionTokens;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.user.UserAccountSessionInvalidator;
import com.repoguard.agent.user.UserLoginAuditRecorder;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public final class DefaultIdentitySessionLifecycle implements IdentitySessionLifecycle {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";
    private static final String EXPIRED_SESSION_MESSAGE = "登录状态已过期，请重新登录";

    private final UserAccountMapper userAccountMapper;
    private final UserRefreshTokenMapper userRefreshTokenMapper;
    private final UserLoginAuditRecorder loginAuditRecorder;
    private final IdentityCredentialAuthenticator credentialAuthenticator;
    private final AuthProperties authProperties;
    private final AuthTokenService authTokenService;
    private final UserAccountSessionInvalidator sessionInvalidator;
    private final RepoGuardMetrics metrics;
    private final TransactionTemplate sessionWriteTransaction;
    private final TransactionTemplate isolatedSessionWriteTransaction;

    @Autowired
    public DefaultIdentitySessionLifecycle(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        IdentityCredentialAuthenticator credentialAuthenticator,
        AuthProperties authProperties,
        AuthTokenService authTokenService,
        UserAccountSessionInvalidator sessionInvalidator,
        RepoGuardMetrics metrics,
        PlatformTransactionManager transactionManager
    ) {
        this(
            userAccountMapper,
            userRefreshTokenMapper,
            loginAuditRecorder,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            sessionInvalidator,
            metrics,
            buildWriteTransaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED),
            buildWriteTransaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );
    }

    public DefaultIdentitySessionLifecycle(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        IdentityCredentialAuthenticator credentialAuthenticator,
        AuthProperties authProperties,
        AuthTokenService authTokenService,
        UserAccountSessionInvalidator sessionInvalidator,
        RepoGuardMetrics metrics
    ) {
        this(
            userAccountMapper,
            userRefreshTokenMapper,
            loginAuditRecorder,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            sessionInvalidator,
            metrics,
            null,
            null
        );
    }

    private DefaultIdentitySessionLifecycle(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        IdentityCredentialAuthenticator credentialAuthenticator,
        AuthProperties authProperties,
        AuthTokenService authTokenService,
        UserAccountSessionInvalidator sessionInvalidator,
        RepoGuardMetrics metrics,
        TransactionTemplate sessionWriteTransaction,
        TransactionTemplate isolatedSessionWriteTransaction
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.userRefreshTokenMapper = Objects.requireNonNull(
            userRefreshTokenMapper,
            "userRefreshTokenMapper must not be null"
        );
        this.loginAuditRecorder = Objects.requireNonNull(loginAuditRecorder, "loginAuditRecorder must not be null");
        this.credentialAuthenticator = Objects.requireNonNull(
            credentialAuthenticator,
            "credentialAuthenticator must not be null"
        );
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
        this.authTokenService = Objects.requireNonNull(authTokenService, "authTokenService must not be null");
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator, "sessionInvalidator must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.sessionWriteTransaction = sessionWriteTransaction;
        this.isolatedSessionWriteTransaction = isolatedSessionWriteTransaction;
    }

    @Override
    public IdentitySessionTokens issue(IdentityAccount account, boolean remember) {
        Objects.requireNonNull(account, "account must not be null");
        return inWriteTransaction(() -> issueTokenPair(account, remember));
    }

    @Override
    public IdentitySessionTokens completeLogin(
        IdentityAccount account,
        String presentedAccount,
        boolean remember
    ) {
        Objects.requireNonNull(account, "account must not be null");
        return inIsolatedWriteTransaction(() -> {
            credentialAuthenticator.recordSuccess(
                account,
                presentedAccount,
                AuthenticationOperation.LOGIN,
                LocalDateTime.now()
            );
            return issueTokenPair(account, remember);
        });
    }

    @Override
    public IdentitySessionTokens reset(
        IdentityAccount account,
        String presentedAccount,
        boolean remember
    ) {
        Objects.requireNonNull(account, "account must not be null");
        return inIsolatedWriteTransaction(() -> {
            LocalDateTime now = LocalDateTime.now();
            sessionInvalidator.revokeActiveRefreshTokens(account.id(), now);
            IdentityAccount rotatedAccount = rotateSessionVersionAndPersist(account, now);
            credentialAuthenticator.recordSuccess(
                rotatedAccount,
                presentedAccount,
                AuthenticationOperation.TOKEN_RESET,
                now
            );
            return issueTokenPair(rotatedAccount, remember);
        });
    }

    @Override
    public RefreshResult refresh(String refreshToken) {
        return inIsolatedWriteTransaction(() -> refreshInWriteTransaction(refreshToken));
    }

    @Override
    public void logout(String refreshToken) {
        inWriteTransaction(() -> {
            UserRefreshToken storedToken = findActiveRefreshToken(refreshToken);
            LocalDateTime now = LocalDateTime.now();
            invalidateLogoutSession(storedToken, now);
            recordAudit(
                storedToken == null ? null : storedToken.getUserId(),
                null,
                "LOGOUT",
                AUDIT_SUCCESS,
                null
            );
            return null;
        });
    }

    @Override
    public void revokeActiveSessions(Long userId, LocalDateTime occurredAt) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        inWriteTransaction(() -> {
            sessionInvalidator.revokeActiveRefreshTokens(userId, occurredAt);
            return null;
        });
    }

    private RefreshResult refreshInWriteTransaction(String refreshToken) {
        UserRefreshToken storedToken = findRefreshToken(refreshToken);
        LocalDateTime now = LocalDateTime.now();
        if (storedToken == null) {
            recordAudit(null, null, "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token expired or invalid");
            return RefreshResult.failure(EXPIRED_SESSION_MESSAGE);
        }

        if (!STATUS_ACTIVE.equals(storedToken.getStatus())) {
            if (isRefreshConcurrencyReplay(storedToken, now)) {
                handleRefreshConcurrencyReplay(storedToken, now);
            } else {
                handleRefreshTokenReuse(storedToken, now);
            }
            return RefreshResult.failure(EXPIRED_SESSION_MESSAGE);
        }
        if (!storedToken.getExpiresAt().isAfter(now)) {
            revokeIfPresent(storedToken, now);
            recordAudit(
                storedToken.getUserId(),
                null,
                "TOKEN_REFRESH",
                AUDIT_FAILURE,
                "refresh token expired or invalid"
            );
            return RefreshResult.failure(EXPIRED_SESSION_MESSAGE);
        }

        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "account unavailable");
            return RefreshResult.failure("账号不可用，请重新登录");
        }
        if (safeSessionVersion(storedToken) != safeSessionVersion(user)) {
            revokeIfPresent(storedToken, now);
            recordAudit(
                storedToken.getUserId(),
                user.getUsername(),
                "TOKEN_REFRESH",
                AUDIT_FAILURE,
                "session version changed"
            );
            return RefreshResult.failure(EXPIRED_SESSION_MESSAGE);
        }

        if (!revokeActiveRefreshToken(storedToken, now)) {
            recordAudit(
                storedToken.getUserId(),
                user.getUsername(),
                "TOKEN_REFRESH",
                AUDIT_FAILURE,
                "refresh token already used"
            );
            return RefreshResult.failure(EXPIRED_SESSION_MESSAGE);
        }

        boolean remember = storedToken.getExpiresAt().isAfter(
            now.plusSeconds(authTokenService.refreshTokenTtlSeconds(false))
        );
        recordAudit(user.getId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_SUCCESS, null);
        return RefreshResult.success(issueTokenPair(toIdentityAccount(user), remember));
    }

    private IdentitySessionTokens issueTokenPair(IdentityAccount account, boolean remember) {
        AuthTokenService.TokenIssue accessToken = authTokenService.issueAccessToken(
            account.id(),
            account.username(),
            account.role(),
            account.sessionVersion()
        );
        AuthTokenService.TokenIssue refreshToken = authTokenService.issueRefreshToken(remember);
        LocalDateTime now = LocalDateTime.now();

        UserRefreshToken storedToken = new UserRefreshToken();
        storedToken.setUserId(account.id());
        storedToken.setTokenHash(authTokenService.hashRefreshToken(refreshToken.token()));
        storedToken.setSessionVersion(account.sessionVersion());
        storedToken.setStatus(STATUS_ACTIVE);
        storedToken.setExpiresAt(now.plusSeconds(refreshToken.expiresInSeconds()));
        storedToken.setCreatedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.insert(storedToken);

        return new IdentitySessionTokens(
            accessToken.token(),
            refreshToken.token(),
            TOKEN_TYPE_BEARER,
            accessToken.expiresInSeconds(),
            refreshToken.expiresInSeconds(),
            account
        );
    }

    private UserRefreshToken findActiveRefreshToken(String refreshToken) {
        UserRefreshToken storedToken = findRefreshToken(refreshToken);
        if (storedToken == null || !STATUS_ACTIVE.equals(storedToken.getStatus())) {
            return null;
        }
        return storedToken;
    }

    private UserRefreshToken findRefreshToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return null;
        }
        return userRefreshTokenMapper.selectOne(new LambdaQueryWrapper<UserRefreshToken>()
            .eq(UserRefreshToken::getTokenHash, authTokenService.hashRefreshToken(refreshToken)));
    }

    private void revokeIfPresent(UserRefreshToken storedToken, LocalDateTime now) {
        if (storedToken == null) {
            return;
        }
        storedToken.setStatus(STATUS_REVOKED);
        storedToken.setRevokedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.updateById(storedToken);
    }

    private void invalidateLogoutSession(UserRefreshToken storedToken, LocalDateTime now) {
        if (storedToken == null) {
            return;
        }
        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        if (user == null || safeSessionVersion(storedToken) != safeSessionVersion(user)) {
            revokeIfPresent(storedToken, now);
            return;
        }
        rotateSessionVersionAndPersist(user, now);
        sessionInvalidator.revokeActiveRefreshTokens(user.getId(), now);
        storedToken.setStatus(STATUS_REVOKED);
        storedToken.setRevokedAt(now);
        storedToken.setUpdatedAt(now);
    }

    private void handleRefreshTokenReuse(UserRefreshToken storedToken, LocalDateTime now) {
        metrics.refreshTokenReuseDetected();
        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        if (user != null && safeSessionVersion(storedToken) == safeSessionVersion(user)) {
            rotateSessionVersionAndPersist(user, now);
        }
        sessionInvalidator.revokeActiveRefreshTokens(storedToken.getUserId(), now);
        storedToken.setLastUsedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("id", storedToken.getId())
            .set("last_used_at", now)
            .set("updated_at", now));
        recordAudit(
            storedToken.getUserId(),
            user == null ? null : user.getUsername(),
            "TOKEN_REFRESH",
            AUDIT_FAILURE,
            "refresh token reuse detected"
        );
    }

    private boolean isRefreshConcurrencyReplay(UserRefreshToken storedToken, LocalDateTime now) {
        long graceSeconds = authProperties.getRefreshConcurrencyGraceSeconds();
        return graceSeconds > 0
            && storedToken.getLastUsedAt() != null
            && !storedToken.getLastUsedAt().isBefore(now.minusSeconds(graceSeconds));
    }

    private void handleRefreshConcurrencyReplay(UserRefreshToken storedToken, LocalDateTime now) {
        metrics.refreshTokenConcurrentReplay();
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("id", storedToken.getId())
            .set("updated_at", now));
        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        recordAudit(
            storedToken.getUserId(),
            user == null ? null : user.getUsername(),
            "TOKEN_REFRESH",
            AUDIT_FAILURE,
            "refresh token replay within concurrency grace"
        );
    }

    private boolean revokeActiveRefreshToken(UserRefreshToken storedToken, LocalDateTime now) {
        int updated = userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("id", storedToken.getId())
            .eq("status", STATUS_ACTIVE)
            .set("status", STATUS_REVOKED)
            .set("revoked_at", now)
            .set("last_used_at", now)
            .set("updated_at", now));
        if (updated <= 0) {
            return false;
        }
        storedToken.setStatus(STATUS_REVOKED);
        storedToken.setRevokedAt(now);
        storedToken.setLastUsedAt(now);
        storedToken.setUpdatedAt(now);
        return true;
    }

    private IdentityAccount rotateSessionVersionAndPersist(IdentityAccount account, LocalDateTime now) {
        IdentityAccount rotated = account.withSessionVersion(account.sessionVersion() + 1);
        UserAccount update = new UserAccount();
        update.setId(rotated.id());
        update.setSessionVersion(rotated.sessionVersion());
        update.setUpdatedAt(now);
        userAccountMapper.updateById(update);
        return rotated;
    }

    private void rotateSessionVersionAndPersist(UserAccount user, LocalDateTime now) {
        sessionInvalidator.rotateSessionVersion(user, now);
        userAccountMapper.updateById(user);
    }

    private IdentityAccount toIdentityAccount(UserAccount user) {
        return new IdentityAccount(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            safeSessionVersion(user)
        );
    }

    private int safeSessionVersion(UserAccount user) {
        return user.getSessionVersion() == null ? 0 : user.getSessionVersion();
    }

    private int safeSessionVersion(UserRefreshToken refreshToken) {
        return refreshToken.getSessionVersion() == null ? 0 : refreshToken.getSessionVersion();
    }

    private void recordAudit(
        Long userId,
        String account,
        String eventType,
        String result,
        String failureReason
    ) {
        loginAuditRecorder.record(userId, account, eventType, result, failureReason);
    }

    private <T> T inWriteTransaction(Supplier<T> operation) {
        if (sessionWriteTransaction == null) {
            return operation.get();
        }
        return sessionWriteTransaction.execute(status -> operation.get());
    }

    private <T> T inIsolatedWriteTransaction(Supplier<T> operation) {
        if (isolatedSessionWriteTransaction == null) {
            return operation.get();
        }
        return isolatedSessionWriteTransaction.execute(status -> operation.get());
    }

    private static TransactionTemplate buildWriteTransaction(
        PlatformTransactionManager transactionManager,
        int propagationBehavior
    ) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        template.setPropagationBehavior(propagationBehavior);
        return template;
    }
}
