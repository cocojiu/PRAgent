package com.repoguard.agent.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserAccountSessionInvalidatorTest {

    private final UserRefreshTokenMapper userRefreshTokenMapper = Mockito.mock(UserRefreshTokenMapper.class);
    private final UserAccountSessionInvalidator invalidator = new UserAccountSessionInvalidator(userRefreshTokenMapper);

    @Test
    void rotatesSessionVersionAndUpdatesTimestamp() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 9, 30);
        UserAccount user = new UserAccount();
        user.setSessionVersion(3);

        invalidator.rotateSessionVersion(user, now);

        assertThat(user.getSessionVersion()).isEqualTo(4);
        assertThat(user.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void treatsNullSessionVersionAsZeroBeforeRotation() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 9, 30);
        UserAccount user = new UserAccount();

        invalidator.rotateSessionVersion(user, now);

        assertThat(user.getSessionVersion()).isEqualTo(1);
        assertThat(user.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void revokesActiveRefreshTokensForUser() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 9, 30);

        invalidator.revokeActiveRefreshTokens(42L, now);

        ArgumentCaptor<Wrapper<UserRefreshToken>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userRefreshTokenMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue()).isInstanceOf(UpdateWrapper.class);
        UpdateWrapper<UserRefreshToken> wrapper = (UpdateWrapper<UserRefreshToken>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status", "revoked_at", "updated_at");
    }
}
