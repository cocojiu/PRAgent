package com.repoguard.agent.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.identity.IdentityCredentialAuthenticator;
import com.repoguard.agent.identity.IdentitySessionInvalidator.SessionInvalidationMode;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.security.AuthTokenService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DefaultIdentitySessionLifecycleInvalidationTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final AuthAccountCache authAccountCache = Mockito.mock(AuthAccountCache.class);
    private final DefaultIdentitySessionLifecycle lifecycle = new DefaultIdentitySessionLifecycle(
        userAccountMapper,
        userRefreshTokenMapper,
        Mockito.mock(IdentityAuditRecorder.class),
        Mockito.mock(IdentityCredentialAuthenticator.class),
        new AuthProperties(),
        Mockito.mock(AuthTokenService.class),
        Mockito.mock(RepoGuardMetrics.class),
        authAccountCache
    );

    @Test
    void refreshTokenOnlyInvalidationLeavesSessionVersionUnchanged() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 19, 14, 0);

        lifecycle.invalidateAccountSessions(42L, SessionInvalidationMode.REFRESH_TOKENS_ONLY, now);

        verify(userAccountMapper, never()).rotateSessionVersion(42L, now);
        verify(authAccountCache, never()).invalidateAfterCommit(42L);
        assertRefreshTokensRevoked();
    }

    @Test
    void sessionVersionOnlyInvalidationUsesAtomicRotation() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 19, 14, 0);
        when(userAccountMapper.rotateSessionVersion(42L, now)).thenReturn(1);

        lifecycle.invalidateAccountSessions(42L, SessionInvalidationMode.SESSION_VERSION_ONLY, now);

        verify(userAccountMapper).rotateSessionVersion(42L, now);
        verify(authAccountCache).invalidateAfterCommit(42L);
        verify(userRefreshTokenMapper, never()).update(isNull(), Mockito.<Wrapper<UserRefreshToken>>any());
    }

    @Test
    void allSessionInvalidationRotatesVersionAndRevokesRefreshTokens() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 19, 14, 0);
        when(userAccountMapper.rotateSessionVersion(42L, now)).thenReturn(1);

        lifecycle.invalidateAccountSessions(42L, SessionInvalidationMode.ALL_SESSIONS, now);

        verify(userAccountMapper).rotateSessionVersion(42L, now);
        verify(authAccountCache).invalidateAfterCommit(42L);
        assertRefreshTokensRevoked();
    }

    @Test
    void sessionVersionInvalidationRejectsMissingAccount() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 19, 14, 0);

        assertThatThrownBy(() -> lifecycle.invalidateAccountSessions(
            42L,
            SessionInvalidationMode.SESSION_VERSION_ONLY,
            now
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Account session version rotation affected 0 rows");
        verify(authAccountCache, never()).invalidateAfterCommit(42L);
    }

    private void assertRefreshTokensRevoked() {
        ArgumentCaptor<Wrapper<UserRefreshToken>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userRefreshTokenMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue()).isInstanceOf(UpdateWrapper.class);
        UpdateWrapper<UserRefreshToken> wrapper = (UpdateWrapper<UserRefreshToken>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status", "revoked_at", "updated_at");
    }
}
