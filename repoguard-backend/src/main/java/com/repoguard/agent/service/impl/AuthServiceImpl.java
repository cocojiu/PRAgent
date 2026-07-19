package com.repoguard.agent.service.impl;

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
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.identity.IdentityAccountLifecycle;
import com.repoguard.agent.identity.IdentityAccountLifecycle.PasswordChangeCommand;
import com.repoguard.agent.identity.IdentityAccountLifecycle.Profile;
import com.repoguard.agent.identity.IdentityAccountLifecycle.RegistrationCommand;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator.AuthenticationOperation;
import com.repoguard.agent.identity.IdentitySessionLifecycle;
import com.repoguard.agent.identity.IdentitySessionLifecycle.RefreshResult;
import com.repoguard.agent.identity.IdentitySessionTokens;
import com.repoguard.agent.service.AuthService;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final IdentityAccountLifecycle accountLifecycle;
    private final IdentityCredentialAuthenticator credentialAuthenticator;
    private final IdentitySessionLifecycle sessionLifecycle;

    public AuthServiceImpl(
        IdentityAccountLifecycle accountLifecycle,
        IdentityCredentialAuthenticator credentialAuthenticator,
        IdentitySessionLifecycle sessionLifecycle
    ) {
        this.accountLifecycle = Objects.requireNonNull(accountLifecycle, "accountLifecycle must not be null");
        this.credentialAuthenticator = Objects.requireNonNull(
            credentialAuthenticator,
            "credentialAuthenticator must not be null"
        );
        this.sessionLifecycle = Objects.requireNonNull(sessionLifecycle, "sessionLifecycle must not be null");
    }

    @Override
    public AuthResponse register(AuthRegisterRequest request) {
        return toAuthResponse(accountLifecycle.register(new RegistrationCommand(
            request.username(),
            request.email(),
            request.password(),
            request.confirmPassword()
        )));
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
        Profile profile = accountLifecycle.currentProfile(userId);
        return new AuthCurrentUserDto(
            profile.id(),
            profile.username(),
            profile.email(),
            profile.role(),
            profile.status(),
            profile.lastLoginAt()
        );
    }

    @Override
    public void changePassword(Long userId, AuthPasswordChangeRequest request) {
        accountLifecycle.changePassword(userId, new PasswordChangeCommand(
            request.currentPassword(),
            request.newPassword(),
            request.confirmPassword()
        ));
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
}
