package com.repoguard.agent.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AuthAccountCache {

    private static final Duration TTL = Duration.ofSeconds(5);
    private static final long MAXIMUM_SIZE = 1024;

    private final UserAccountMapper userAccountMapper;
    private final Cache<Long, Optional<UserAccount>> accounts = Caffeine.newBuilder()
        .expireAfterWrite(TTL)
        .maximumSize(MAXIMUM_SIZE)
        .build();

    public AuthAccountCache(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = Objects.requireNonNull(userAccountMapper, "userAccountMapper must not be null");
    }

    public UserAccount findById(Long userId) {
        return accounts.get(userId, id -> Optional.ofNullable(userAccountMapper.selectById(id))).orElse(null);
    }

    public void invalidateAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateNow(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateNow(userId);
            }
        });
    }

    private void invalidateNow(Long userId) {
        accounts.invalidate(userId);
    }
}
