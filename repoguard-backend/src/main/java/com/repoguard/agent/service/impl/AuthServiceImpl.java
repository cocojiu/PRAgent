package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthPasswordChangeRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator.AuthenticationOperation;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.IdentitySessionLifecycle.RefreshResult;
import com.repoguard.agent.identity.IdentitySessionTokens;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.user.UserLoginAuditRecorder;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String ROLE_VIEWER = "VIEWER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String AUDIT_SUCCESS = "SUCCESS";
    private static final String AUDIT_FAILURE = "FAILURE";

    private final UserAccountMapper userAccountMapper;
    private final UserLoginAuditRecorder loginAuditRecorder;
    private final PasswordHashService passwordHashService;
    private final IdentityCredentialAuthenticator credentialAuthenticator;
    private final IdentitySessionLifecycle sessionLifecycle;
    private final AuthProperties authProperties;
    private final TransactionTemplate authWriteTransaction;

    @Autowired
    public AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
        IdentityCredentialAuthenticator credentialAuthenticator,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties,
        PlatformTransactionManager transactionManager
    ) {
        this(
            userAccountMapper,
            loginAuditRecorder,
            passwordHashService,
            credentialAuthenticator,
            sessionLifecycle,
            authProperties,
            buildWriteTransaction(transactionManager)
        );
    }

    public AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
        IdentityCredentialAuthenticator credentialAuthenticator,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties
    ) {
        this(
            userAccountMapper,
            loginAuditRecorder,
            passwordHashService,
            credentialAuthenticator,
            sessionLifecycle,
            authProperties,
            (TransactionTemplate) null
        );
    }

    private AuthServiceImpl(
        UserAccountMapper userAccountMapper,
        UserLoginAuditRecorder loginAuditRecorder,
        PasswordHashService passwordHashService,
        IdentityCredentialAuthenticator credentialAuthenticator,
        IdentitySessionLifecycle sessionLifecycle,
        AuthProperties authProperties,
        TransactionTemplate authWriteTransaction
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.loginAuditRecorder = Objects.requireNonNull(loginAuditRecorder, "loginAuditRecorder must not be null");
        this.passwordHashService = Objects.requireNonNull(passwordHashService, "passwordHashService must not be null");
        this.credentialAuthenticator = Objects.requireNonNull(
            credentialAuthenticator,
            "credentialAuthenticator must not be null"
        );
        this.sessionLifecycle = Objects.requireNonNull(sessionLifecycle, "sessionLifecycle must not be null");
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
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
            return toAuthResponse(sessionLifecycle.issue(toIdentityAccount(user), false));
        });
    }

    @Override
    public AuthResponse login(AuthLoginRequest request) {
        IdentityAccount account = credentialAuthenticator.authenticate(
            request.account(),
            request.password(),
            AuthenticationOperation.LOGIN
        );
        return toAuthResponse(sessionLifecycle.completeLogin(
            account,
            request.account(),
            Boolean.TRUE.equals(request.remember())
        ));
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
            recordAuditInWriteTransaction(
                userId,
                user == null ? null : user.getUsername(),
                "PASSWORD_CHANGE",
                "bad credentials"
            );
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
            sessionLifecycle.revokeActiveSessions(user.getId(), now);
            recordAudit(user.getId(), user.getUsername(), "PASSWORD_CHANGE", AUDIT_SUCCESS, null);
            return null;
        });
    }

    @Override
    public AuthResponse refresh(AuthRefreshRequest request) {
        RefreshResult result = sessionLifecycle.refresh(request.refreshToken());
        if (result.failed()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, result.failureMessage());
        }
        return toAuthResponse(result.tokens());
    }

    @Override
    public AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request) {
        IdentityAccount account = credentialAuthenticator.authenticate(
            request.account(),
            request.password(),
            AuthenticationOperation.TOKEN_RESET
        );
        return toAuthResponse(sessionLifecycle.reset(
            account,
            request.account(),
            Boolean.TRUE.equals(request.remember())
        ));
    }

    @Override
    public void logout(AuthLogoutRequest request) {
        sessionLifecycle.logout(request.refreshToken());
    }

    private AuthResponse toAuthResponse(IdentitySessionTokens session) {
        IdentityAccount account = session.account();
        return new AuthResponse(
            session.accessToken(),
            session.refreshToken(),
            session.tokenType(),
            session.accessTokenExpiresInSeconds(),
            session.refreshTokenExpiresInSeconds(),
            new AuthUserDto(account.id(), account.username(), account.email(), account.role())
        );
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
        loginAuditRecorder.record(userId, account, eventType, result, failureReason);
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
        if (authWriteTransaction == null) {
            return operation.get();
        }
        return authWriteTransaction.execute(status -> operation.get());
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
