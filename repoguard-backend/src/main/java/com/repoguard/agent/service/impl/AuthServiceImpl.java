package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.web.AuditClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";
    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long ACCOUNT_LOCK_MINUTES = 15;

    private final UserAccountMapper userAccountMapper;
    private final UserRefreshTokenMapper userRefreshTokenMapper;
    private final UserLoginAuditMapper userLoginAuditMapper;
    private final PasswordHashService passwordHashService;
    private final AuthProperties authProperties;
    private final AuthTokenService authTokenService;

    public AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserRefreshTokenMapper userRefreshTokenMapper,
        UserLoginAuditMapper userLoginAuditMapper,
        PasswordHashService passwordHashService,
        AuthProperties authProperties,
        AuthTokenService authTokenService
    ) {
        this.userAccountMapper = userAccountMapper;
        this.userRefreshTokenMapper = userRefreshTokenMapper;
        this.userLoginAuditMapper = userLoginAuditMapper;
        this.passwordHashService = passwordHashService;
        this.authProperties = authProperties;
        this.authTokenService = authTokenService;
    }

    @Override
    @Transactional
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

        LocalDateTime now = LocalDateTime.now();
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHashService.hash(request.password()));
        user.setRole(ROLE_VIEWER);
        user.setStatus(STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastLoginAt(now);
        try {
            userAccountMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或邮箱已存在");
        }
        recordAudit(user.getId(), user.getUsername(), "REGISTER", AUDIT_SUCCESS, null);
        return issueTokenPair(user, false);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(AuthLoginRequest request) {
        UserAccount user = verifyCredentials(request.account(), request.password(), "LOGIN");
        LocalDateTime now = LocalDateTime.now();
        clearLoginFailures(user, now);
        recordAudit(user.getId(), request.account(), "LOGIN", AUDIT_SUCCESS, null);
        return issueTokenPair(user, Boolean.TRUE.equals(request.remember()));
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
    @Transactional
    public AuthResponse refresh(AuthRefreshRequest request) {
        UserRefreshToken storedToken = findActiveRefreshToken(request.refreshToken());
        LocalDateTime now = LocalDateTime.now();
        if (storedToken == null || !storedToken.getExpiresAt().isAfter(now)) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken == null ? null : storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token expired or invalid");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已过期，请重新登录");
        }

        UserAccount user = userAccountMapper.selectById(storedToken.getUserId());
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            revokeIfPresent(storedToken, now);
            recordAudit(storedToken.getUserId(), null, "TOKEN_REFRESH", AUDIT_FAILURE, "account unavailable");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号不可用，请重新登录");
        }

        if (!revokeActiveRefreshToken(storedToken, now)) {
            recordAudit(storedToken.getUserId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_FAILURE, "refresh token already used");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已过期，请重新登录");
        }

        boolean remember = storedToken.getExpiresAt().isAfter(now.plusSeconds(authTokenService.refreshTokenTtlSeconds(false)));
        recordAudit(user.getId(), user.getUsername(), "TOKEN_REFRESH", AUDIT_SUCCESS, null);
        return issueTokenPair(user, remember);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request) {
        UserAccount user = verifyCredentials(request.account(), request.password(), "TOKEN_RESET");
        LocalDateTime now = LocalDateTime.now();
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("user_id", user.getId())
            .eq("status", STATUS_ACTIVE)
            .set("status", STATUS_REVOKED)
            .set("revoked_at", now)
            .set("updated_at", now));
        recordAudit(user.getId(), request.account(), "TOKEN_RESET", AUDIT_SUCCESS, null);
        return issueTokenPair(user, Boolean.TRUE.equals(request.remember()));
    }

    @Override
    @Transactional
    public void logout(AuthLogoutRequest request) {
        UserRefreshToken storedToken = findActiveRefreshToken(request.refreshToken());
        revokeIfPresent(storedToken, LocalDateTime.now());
        recordAudit(storedToken == null ? null : storedToken.getUserId(), null, "LOGOUT", AUDIT_SUCCESS, null);
    }

    private UserAccount verifyCredentials(String accountValue, String password, String eventType) {
        String account = accountValue.trim();
        UserAccount user = account.contains("@") ? findByEmail(account.toLowerCase(Locale.ROOT)) : findByUsername(account);
        if (user != null && isLocked(user)) {
            recordAudit(user.getId(), account, eventType, AUDIT_FAILURE, "account locked");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已暂时锁定，请 15 分钟后再试");
        }
        if (user == null || !passwordHashService.matches(password, user.getPasswordHash())) {
            handleFailedCredentialAttempt(user, account, eventType, "bad credentials");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            recordAudit(user.getId(), account, eventType, AUDIT_FAILURE, "account disabled");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已被禁用，请联系管理员");
        }
        return user;
    }

    private AuthResponse issueTokenPair(UserAccount user, boolean remember) {
        AuthTokenService.TokenIssue accessToken = authTokenService.issueAccessToken(user);
        AuthTokenService.TokenIssue refreshToken = authTokenService.issueRefreshToken(remember);
        LocalDateTime now = LocalDateTime.now();

        UserRefreshToken storedToken = new UserRefreshToken();
        storedToken.setUserId(user.getId());
        storedToken.setTokenHash(authTokenService.hashRefreshToken(refreshToken.token()));
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
        if (!StringUtils.hasText(refreshToken)) {
            return null;
        }
        return userRefreshTokenMapper.selectOne(new LambdaQueryWrapper<UserRefreshToken>()
            .eq(UserRefreshToken::getTokenHash, authTokenService.hashRefreshToken(refreshToken))
            .eq(UserRefreshToken::getStatus, STATUS_ACTIVE));
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

    private void handleFailedCredentialAttempt(UserAccount user, String account, String eventType, String reason) {
        if (user == null) {
            recordAudit(null, account, eventType, AUDIT_FAILURE, reason);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int failedCount = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        LocalDateTime lockedUntil = null;
        if (failedCount >= MAX_FAILED_LOGIN_ATTEMPTS) {
            lockedUntil = now.plusMinutes(ACCOUNT_LOCK_MINUTES);
        }
        persistFailedLoginAttempt(user, failedCount, lockedUntil, now);
        recordAudit(user.getId(), account, eventType, AUDIT_FAILURE, reason);
    }

    private void persistFailedLoginAttempt(UserAccount user, int failedCount, LocalDateTime lockedUntil, LocalDateTime now) {
        user.setFailedLoginCount(failedCount);
        user.setLockedUntil(lockedUntil);
        user.setUpdatedAt(now);
        UpdateWrapper<UserAccount> update = new UpdateWrapper<UserAccount>()
            .eq("id", user.getId())
            .set("failed_login_count", failedCount)
            .set("locked_until", lockedUntil)
            .set("updated_at", now);
        userAccountMapper.update(null, update);
    }

    private void clearLoginFailures(UserAccount user, LocalDateTime now) {
        user.setLastLoginAt(now);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);
        UpdateWrapper<UserAccount> update = new UpdateWrapper<UserAccount>()
            .eq("id", user.getId())
            .set("last_login_at", now)
            .set("failed_login_count", 0)
            .set("locked_until", null)
            .set("updated_at", now);
        userAccountMapper.update(null, update);
    }

    private boolean isLocked(UserAccount user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
    }

    private void recordAudit(Long userId, String account, String eventType, String result, String failureReason) {
        UserLoginAudit audit = new UserLoginAudit();
        audit.setUserId(userId);
        audit.setAccount(account);
        audit.setEventType(eventType);
        audit.setResult(result);
        audit.setFailureReason(failureReason);
        audit.setCreatedAt(LocalDateTime.now());

        HttpServletRequest request = currentRequest();
        if (request != null) {
            audit.setClientIp(AuditClientIpResolver.resolve(request));
            audit.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
        }
        userLoginAuditMapper.insert(audit);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
}
