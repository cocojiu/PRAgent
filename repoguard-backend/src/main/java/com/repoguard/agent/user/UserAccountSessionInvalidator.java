package com.repoguard.agent.user;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class UserAccountSessionInvalidator {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";

    private final UserRefreshTokenMapper userRefreshTokenMapper;

    public UserAccountSessionInvalidator(UserRefreshTokenMapper userRefreshTokenMapper) {
        this.userRefreshTokenMapper =
            Objects.requireNonNull(userRefreshTokenMapper, "userRefreshTokenMapper must not be null");
    }

    public void rotateSessionVersion(UserAccount user, LocalDateTime now) {
        user.setSessionVersion((user.getSessionVersion() == null ? 0 : user.getSessionVersion()) + 1);
        user.setUpdatedAt(now);
    }

    public void revokeActiveRefreshTokens(Long userId, LocalDateTime now) {
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("user_id", userId)
            .eq("status", STATUS_ACTIVE)
            .set("status", STATUS_REVOKED)
            .set("revoked_at", now)
            .set("updated_at", now));
    }
}
