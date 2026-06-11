package com.repoguard.agent.service;

import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;

public interface AuthService {

    AuthResponse register(AuthRegisterRequest request);

    AuthResponse login(AuthLoginRequest request);

    AuthResponse refresh(AuthRefreshRequest request);

    AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request);

    void logout(AuthLogoutRequest request);
}
