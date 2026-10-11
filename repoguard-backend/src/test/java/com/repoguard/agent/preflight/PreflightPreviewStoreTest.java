package com.repoguard.agent.preflight;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.mapper.PreflightPreviewMapper;
import com.repoguard.agent.security.PreflightCredentialService.Grant;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class PreflightPreviewStoreTest {
    private final PreflightPreviewMapper mapper = mock(PreflightPreviewMapper.class);
    private final SecretCryptoService crypto = new SecretCryptoService(UUID.randomUUID().toString());
    private final PreflightPreviewStore store = new PreflightPreviewStore(mapper, crypto);
    private final Grant grant = new Grant(7L, 3, 1, "fixture/repo", true, Long.MAX_VALUE, UUID.randomUUID().toString());
    private final String sha = "a".repeat(64);

    private void permit() {
        when(mapper.lockTenant(1)).thenReturn(1L);
        when(mapper.claim(1, 7, sha, grant.nonce(), grant.repository(), true)).thenReturn(1);
    }
    @Test void admissionLocksTenantAndCreatesExactlyOneIndependentRow() {
        permit();
        assertThat(store.claim(grant, sha, true)).isNull();
        var order = inOrder(mapper);
        order.verify(mapper).lockTenant(1); order.verify(mapper).purgeResponses(1);
        order.verify(mapper).purgeExpired(1); order.verify(mapper).exists(1, 7, sha);
        order.verify(mapper).countRecent(1, false); order.verify(mapper).countRecent(1, true);
        order.verify(mapper).claim(1, 7, sha, grant.nonce(), grant.repository(), true);
    }
    @Test void completedRetryReturnsEncryptedSummaryWithoutNewClaim() {
        permit(); when(mapper.exists(1, 7, sha)).thenReturn(1);
        when(mapper.result(1, 7, sha)).thenReturn(crypto.encrypt("{\"scope\":\"ONLINE_PREFLIGHT\"}"));
        assertThat(store.claim(grant, sha, true)).contains("ONLINE_PREFLIGHT");
        verify(mapper, never()).claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }
    @Test void runningFailedAndExpiredResultCannotTriggerAnotherExecution() {
        permit(); when(mapper.exists(1, 7, sha)).thenReturn(1);
        assertThatThrownBy(() -> store.claim(grant, sha, true)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }
    @Test void dailyTenantAndModelCapsFailBeforeInsert() {
        permit(); when(mapper.countRecent(1, false)).thenReturn(20);
        assertThatThrownBy(() -> store.claim(grant, sha, true)).isInstanceOf(BusinessException.class);
        when(mapper.countRecent(1, false)).thenReturn(1); when(mapper.countRecent(1, true)).thenReturn(5);
        assertThatThrownBy(() -> store.claim(grant, sha, true)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }
    @Test void consumedCredentialAndLostCompletionFenceAreRejected() {
        permit(); when(mapper.claim(1, 7, sha, grant.nonce(), grant.repository(), true))
            .thenThrow(new DuplicateKeyException("fixture"));
        assertThatThrownBy(() -> store.claim(grant, sha, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.complete(grant, sha, "fixture")).isInstanceOf(IllegalStateException.class);
    }
    @Test void summaryIsEncryptedBeforeStorageAndCleanupTouchesOnlyExpirationStatements() {
        when(mapper.complete(eq(1L), eq(7L), eq(sha), anyString())).thenReturn(1);
        store.complete(grant, sha, "fixture");
        var cipher = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).complete(eq(1L), eq(7L), eq(sha), cipher.capture());
        assertThat(cipher.getValue()).startsWith("enc:").doesNotContain("fixture");
        assertThat(crypto.decrypt(cipher.getValue())).isEqualTo("fixture");
        store.cleanupExpired();
        verify(mapper).purgeGlobalResponses(); verify(mapper).purgeGlobalExpired();
    }
}
