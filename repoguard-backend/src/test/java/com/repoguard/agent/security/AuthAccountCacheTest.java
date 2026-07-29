package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthAccountCacheTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final AuthAccountCache cache = new AuthAccountCache(userAccountMapper);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void invalidationWaitsForTransactionCommit() {
        UserAccount beforeCommit = account("ACTIVE", 1);
        UserAccount afterCommit = account("ACTIVE", 2);
        when(userAccountMapper.selectById(42L)).thenReturn(beforeCommit, afterCommit);
        assertThat(cache.findById(42L)).isSameAs(beforeCommit);
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAfterCommit(42L);

        assertThat(cache.findById(42L)).isSameAs(beforeCommit);
        verify(userAccountMapper).selectById(42L);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(cache.findById(42L)).isSameAs(afterCommit);
        verify(userAccountMapper, times(2)).selectById(42L);
    }

    @Test
    void rollbackKeepsExistingCacheEntry() {
        UserAccount cached = account("ACTIVE", 1);
        when(userAccountMapper.selectById(42L)).thenReturn(cached);
        assertThat(cache.findById(42L)).isSameAs(cached);
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAfterCommit(42L);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        assertThat(cache.findById(42L)).isSameAs(cached);
        verify(userAccountMapper).selectById(42L);
    }

    @Test
    void invalidationWithoutTransactionReloadsImmediately() {
        UserAccount initial = account("ACTIVE", 1);
        UserAccount reloaded = account("DISABLED", 2);
        when(userAccountMapper.selectById(42L)).thenReturn(initial, reloaded);
        assertThat(cache.findById(42L)).isSameAs(initial);

        cache.invalidateAfterCommit(42L);

        assertThat(cache.findById(42L)).isSameAs(reloaded);
        verify(userAccountMapper, times(2)).selectById(42L);
    }

    private UserAccount account(String status, int sessionVersion) {
        UserAccount account = new UserAccount();
        account.setId(42L);
        account.setStatus(status);
        account.setSessionVersion(sessionVersion);
        return account;
    }
}
