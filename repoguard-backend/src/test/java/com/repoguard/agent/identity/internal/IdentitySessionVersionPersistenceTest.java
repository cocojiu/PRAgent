package com.repoguard.agent.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.security.AuthAccountCache;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class IdentitySessionVersionPersistenceTest {

    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final AuthAccountCache authAccountCache = new AuthAccountCache(userAccountMapper);
    private final IdentitySessionVersionPersistence persistence = new IdentitySessionVersionPersistence(
        userAccountMapper,
        authAccountCache
    );

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void atomicRotationKeepsCachedAccountUntilCommitThenInvalidatesIt() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 30, 9, 0);
        UserAccount beforeCommit = account(1);
        UserAccount afterCommit = account(2);
        when(userAccountMapper.selectById(42L)).thenReturn(beforeCommit, afterCommit);
        when(userAccountMapper.rotateSessionVersion(42L, now)).thenReturn(1);

        assertThat(authAccountCache.findById(42L)).isSameAs(beforeCommit);
        TransactionSynchronizationManager.initSynchronization();

        persistence.rotatePersisted(42L, now);

        assertThat(authAccountCache.findById(42L)).isSameAs(beforeCommit);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(authAccountCache.findById(42L)).isSameAs(afterCommit);
        verify(userAccountMapper).rotateSessionVersion(42L, now);
    }

    private UserAccount account(int sessionVersion) {
        UserAccount account = new UserAccount();
        account.setId(42L);
        account.setSessionVersion(sessionVersion);
        return account;
    }
}
