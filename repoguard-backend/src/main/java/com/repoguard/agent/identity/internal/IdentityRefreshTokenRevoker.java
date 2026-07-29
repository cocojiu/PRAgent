package com.repoguard.agent.identity.internal;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.UserRefreshToken;
import com.repoguard.agent.mapper.UserRefreshTokenMapper;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Owns refresh-token revocation and replay-marker persistence.
 */
final class IdentityRefreshTokenRevoker {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";

    private final UserRefreshTokenMapper userRefreshTokenMapper;

    IdentityRefreshTokenRevoker(UserRefreshTokenMapper userRefreshTokenMapper) {
        this.userRefreshTokenMapper = Objects.requireNonNull(
            userRefreshTokenMapper,
            "userRefreshTokenMapper must not be null"
        );
    }

    void revokeIfPresent(UserRefreshToken storedToken, LocalDateTime now) {
        if (storedToken == null) {
            return;
        }
        storedToken.setStatus(STATUS_REVOKED);
        storedToken.setRevokedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.updateById(storedToken);
    }

    boolean revokeActive(UserRefreshToken storedToken, LocalDateTime now) {
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

    void revokeActiveForAccount(Long userId, LocalDateTime now) {
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("user_id", userId)
            .eq("status", STATUS_ACTIVE)
            .set("status", STATUS_REVOKED)
            .set("revoked_at", now)
            .set("updated_at", now));
    }

    void recordReuse(UserRefreshToken storedToken, LocalDateTime now) {
        storedToken.setLastUsedAt(now);
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("id", storedToken.getId())
            .set("last_used_at", now)
            .set("updated_at", now));
    }

    void recordConcurrentReplay(UserRefreshToken storedToken, LocalDateTime now) {
        storedToken.setUpdatedAt(now);
        userRefreshTokenMapper.update(null, new UpdateWrapper<UserRefreshToken>()
            .eq("id", storedToken.getId())
            .set("updated_at", now));
    }
}
