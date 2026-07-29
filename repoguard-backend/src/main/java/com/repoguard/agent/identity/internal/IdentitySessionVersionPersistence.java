package com.repoguard.agent.identity.internal;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.identity.IdentityAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthAccountCache;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Owns session-version persistence and schedules authentication-cache invalidation after commit.
 */
final class IdentitySessionVersionPersistence {

    private final UserAccountMapper userAccountMapper;
    private final AuthAccountCache authAccountCache;

    IdentitySessionVersionPersistence(
        UserAccountMapper userAccountMapper,
        AuthAccountCache authAccountCache
    ) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
        this.authAccountCache = Objects.requireNonNull(authAccountCache, "authAccountCache must not be null");
    }

    IdentityAccount rotateAndPersist(IdentityAccount account, LocalDateTime now) {
        IdentityAccount rotated = account.withSessionVersion(account.sessionVersion() + 1);
        UserAccount update = new UserAccount();
        update.setId(rotated.id());
        update.setSessionVersion(rotated.sessionVersion());
        update.setUpdatedAt(now);
        userAccountMapper.updateById(update);
        authAccountCache.invalidateAfterCommit(rotated.id());
        return rotated;
    }

    void rotateAndPersist(UserAccount user, LocalDateTime now) {
        user.setSessionVersion(safeSessionVersion(user) + 1);
        user.setUpdatedAt(now);
        userAccountMapper.updateById(user);
        authAccountCache.invalidateAfterCommit(user.getId());
    }

    void rotatePersisted(Long userId, LocalDateTime now) {
        int updated = userAccountMapper.rotateSessionVersion(userId, now);
        if (updated != 1) {
            throw new IllegalStateException("Account session version rotation affected " + updated + " rows");
        }
        authAccountCache.invalidateAfterCommit(userId);
    }

    private int safeSessionVersion(UserAccount user) {
        return user.getSessionVersion() == null ? 0 : user.getSessionVersion();
    }
}
