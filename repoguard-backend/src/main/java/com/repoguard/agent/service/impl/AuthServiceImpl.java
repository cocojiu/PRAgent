package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthPasswordChangeRequest;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator.AuthenticationOperation;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.user.UserLoginAuditRecorder;
import com.repoguard.agent.user.UserAccountSessionInvalidator;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";
    private final UserAccountMapper userAccountMapper;
    private final UserRefreshTokenMapper userRefreshTokenMapper;
    private final UserLoginAuditRecorder loginAuditRecorder;
    private final PasswordHashService passwordHashService;
    private final IdentityCredentialAuthenticator credentialAuthenticator;
    private final AuthProperties authProperties;
    private final AuthTokenService authTokenService;
    private final UserAccountSessionInvalidator sessionInvalidator;
    private final RepoGuardMetrics metrics;
    private final TransactionTemplate authWriteTransaction;

    @Autowired
    public AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
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
            passwordHashService,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            sessionInvalidator,
            metrics,
            buildWriteTransaction(transactionManager)
        );
    }

    public AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
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
            passwordHashService,
            credentialAuthenticator,
            authProperties,
            authTokenService,
            sessionInvalidator,
            metrics,
            (TransactionTemplate) null
        );
    }

    private AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
        IdentityCredentialAuthenticator credentialAuthenticator,
        AuthProperties authProperties,
        AuthTokenService authTokenService,
        UserAccountSessionInvalidator sessionInvalidator,
        RepoGuardMetrics metrics,
        TransactionTemplate authWriteTransaction
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRefreshTokenMapper = userRefreshTokenMapper;
        this.loginAuditRecorder = Objects.requireNonNull(loginAuditRecorder, "loginAuditRecorder must not be null");
        this.passwordHashService = passwordHashService;
        this.credentialAuthenticator = Objects.requireNonNull(
            credentialAuthenticator,
            "credentialAuthenticator must not be null"
        );
        this.authProperties = authProperties;
        this.authTokenService = authTokenService;
        this.sessionInvalidator = Objects.requireNonNull(sessionInvalidator, "sessionInvalidator must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.authWriteTransaction = authWriteTransaction;
    }

    @Override
    public AuthResponse register(AuthRegisterRequest request) {
        if (!authProperties.isRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "公开注册已关闭，请联系管理员开通账号");
        }
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

        String passwordHash = passwordHashService.hash(request.password());
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
            return issueTokenPair(user, false);
        });
    }

    @Override
    public AuthResponse login(AuthLoginRequest request) {
        UserAccount user = credentialAuthenticator.authenticate(
            request.account(),
            request.password(),
            AuthenticationOperation.LOGIN
        );
        return inWriteTransaction(() -> {
            LocalDateTime now = LocalDateTime.now();
            credentialAuthenticator.recordSuccess(
                user,
                request.account(),
                AuthenticationOperation.LOGIN,
                now
            );
            return issueTokenPair(user, Boolean.TRUE.equals(request.remember()));
        });
    }

    @Override
    public AuthCurrentUserDto currentUser(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不可用，请重新登录");
        }
        return new AuthCurrentUserDto(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole(),
            user.getStatus(),
            user.getLastLoginAt()
        );
    }

    @Override
    public void changePassword(Long userId, AuthPasswordChangeRequest request) {
        UserAccount user = userAccountMapper.selectById(userId);
        boolean currentPasswordMatches = passwordHashService.matchesOrDummy(
            request.currentPassword(),
            user == null ? null : user.getPasswordHash()
        );
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus()) || !currentPasswordMatches) {
            recordAuditInWriteTransaction(userId, user == null ? null : user.getUsername(), "PASSWORD_CHANGE", "bad credentials");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Current password is incorrect");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password and confirmation do not match");
        }
        if (!isStrongEnough(request.newPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password must contain both letters and numbers");
        }
        if (passwordHashService.matchesOrDummy(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "New password must differ from the current password");
        }

        String currentPasswordHash = user.getPasswordHash();
        String newPasswordHash = passwordHashService.hash(request.newPassword());
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
            sessionInvalidator.revokeActiveRefreshTokens(user.getId(), now);
            recordAudit(user.getId(), user.getUsername(), "PASSWORD_CHANGE", AUDIT_SUCCESS, null);
            return null;
        });
    }

    @Override
    public AuthResponse refresh(AuthRefreshRequest request) {
        RefreshTransactionResult result = inWriteTransaction(() -> refreshInWriteTransaction(request));
        if (result.failureMessage() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, result.failureMessage());
        }
        return result.response();
    }

    private RefreshTransactionResult refreshInWriteTransaction(AuthRefreshRequest request) {
        UserRefreshToken storedToken = findRefreshToken(request.refreshToken());
        LocalDateTime now = LocalDateTime.now();
        if (storedToken == null) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken == null ? null : storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token expired or invalid");
            return RefreshTransactionResult.failure("登录状态已过期，请重新登录");
        }

        if (!STATUS_ACTIVE.equals(storedToken.getStatus())) {
            if (isRefreshConcurrencyReplay(storedToken, now)) {
                handleRefreshConcurrencyReplay(storedToken, now);
            } else {
                handleRefreshTokenReuse(storedToken, now);
            }
            return RefreshTransactionResult.failure("登录状态已过期，请重新登录");
        }
        if (!storedToken.getExpiresAt().isAfter(now)) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token expired or invalid");
            return RefreshTransactionResult.failure("登录状态已过期，请重新登录");
        }

        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "account unavailable");
            return RefreshTransactionResult.failure("账号不可用，请重新登录");
        }
        if (safeSessionVersion(storedToken) != safeSessionVersion(user)) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken.getUserId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_FAILURE, "session version changed");
            return RefreshTransactionResult.failure("登录状态已过期，请重新登录");
        }

        if (!revokeActiveRefreshToken(storedToken, now)) {
            recordAudit(storedToken.getUserId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token already used");
            return RefreshTransactionResult.failure("登录状态已过期，请重新登录");
        }

        boolean remember = storedToken.getExpiresAt().isAfter(now.plusSeconds(authTokenService.refreshTokenTtlSeconds(false)));
        recordAudit(user.getId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_SUCCESS, null);
        return RefreshTransactionResult.success(issueTokenPair(user, remember));
    }

    @Override
    public AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request) {
        UserAccount user = credentialAuthenticator.authenticate(
            request.account(),
            request.password(),
            AuthenticationOperation.TOKEN_RESET
        );
        return inWriteTransaction(() -> {
            LocalDateTime now = LocalDateTime.now();
            userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
                .eq("user_id", user.getId())
                .eq("status", STATUS_ACTIVE)
                .set("status", STATUS_REVOKED)
                .set("revoked_at", now)
                .set("updated_at", now));
            rotateSessionVersionAndPersist(user, now);
            credentialAuthenticator.recordSuccess(
                user,
                request.account(),
                AuthenticationOperation.TOKEN_RESET,
                now
            );
            return issueTokenPair(user, Boolean.TRUE.equals(request.remember()));
        });
    }

    @Override
    @Transactional
    public void logout(AuthLogoutRequest request) {
        UserRefreshToken storedToken = findActiveRefreshToken(request.refreshToken());
        LocalDateTime now = LocalDateTime.now();
        invalidateLogoutSession(storedToken, now);
        recordAudit(storedToken == null ? null : storedToken.getUserId(), null, "LOGOUT", AUDIT_SUCCESS, null);
    }

    private AuthResponse issueTokenPair(UserAccount user, boolean remember) {
        AuthTokenService.TokenIssue accessToken = authTokenService.issueAccessToken(user);
        AuthTokenService.TokenIssue refreshToken = authTokenService.issueRefreshToken(remember);
        LocalDateTime now = LocalDateTime.now();

        UserRefreshToken storedToken = new UserRefreshToken();
        storedToken.setUserId(user.getId());
        storedToken.setTokenHash(authTokenService.hashRefreshToken(refreshToken.token()));
        storedToken.setSessionVersion(safeSessionVersion(user));
        storedToken.setStatus(STATUS_ACTIVE);
        storedToken.setExpiresAt(now.plusSeconds(refreshToken.expiresInSeconds()));
        storedToken.setCreatedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.insert(storedToken);

        return new AuthResponse(
            accessToken.token(),
            refreshToken.token(),
            TOKEN_TYPE_BEARER,
            accessToken.expiresInSeconds(),
            refreshToken.expiresInSeconds(),
            new AuthUserDto(user.getId(), user.getUsername(), user.getEmail(), user.getRole())
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

    private void rotateSessionVersionAndPersist(UserAccount user, LocalDateTime now) {
        sessionInvalidator.rotateSessionVersion(user, now);
        userAccountMapper.updateById(user);
    }

    private int safeSessionVersion(UserAccount user) {
        return user.getSessionVersion() == null ? 0 : user.getSessionVersion();
    }

    private int safeSessionVersion(UserRefreshToken refreshToken) {
        return refreshToken.getSessionVersion() == null ? 0 : refreshToken.getSessionVersion();
    }

    private void recordAudit(Long userId, String account, String eventType, String result, String failureReason) {
        loginAuditRecorder.record(userId, account, eventType, result, failureReason);
    }

    private void recordAuditInWriteTransaction(Long userId, String account, String eventType, String failureReason) {
        inWriteTransaction(() -> {
            recordAudit(userId, account, eventType, AUDIT_FAILURE, failureReason);
            return null;
        });
    }

    private <T> T inWriteTransaction(Supplier<T> operation) {
        if (authWriteTransaction == null) {
            return operation.get();
        }
        return authWriteTransaction.execute(status -> operation.get());
    }

    private static TransactionTemplate buildWriteTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private UserAccount findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
    }

    private UserAccount findByEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        return userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getEmail, email));
    }

    private boolean isStrongEnough(String password) {
        return password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
    }

    private record RefreshTransactionResult(AuthResponse response, String failureMessage) {

        private static RefreshTransactionResult success(AuthResponse response) {
            return new RefreshTransactionResult(Objects.requireNonNull(response, "response must not be null"), null);
        }

        private static RefreshTransactionResult failure(String failureMessage) {
            return new RefreshTransactionResult(null, Objects.requireNonNull(failureMessage, "failureMessage must not be null"));
        }
    }
}
