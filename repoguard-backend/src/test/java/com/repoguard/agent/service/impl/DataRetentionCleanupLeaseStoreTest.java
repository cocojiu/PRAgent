package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.DataRetentionCleanupLease;
import com.repoguard.agent.mapper.DataRetentionCleanupLeaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataRetentionCleanupLeaseStoreTest {

    private final DataRetentionCleanupLeaseMapper leaseMapper = org.mockito.Mockito.mock(
        DataRetentionCleanupLeaseMapper.class
    );
    private final DataRetentionCleanupLeaseStore store = new DataRetentionCleanupLeaseStore(leaseMapper);

    @Test
    void constructorRejectsMissingMapper() {
        assertThatThrownBy(() -> new DataRetentionCleanupLeaseStore(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("leaseMapper");
    }

    @Test
    void acquireClaimsExpiredLeaseWithFreshOwner() {
        when(leaseMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        DataRetentionCleanupLeaseStore.Lease lease = store.acquire();

        assertThat(lease).isNotNull();
        assertThat(lease.ownerId()).isNotBlank();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<DataRetentionCleanupLease>> wrapperCaptor = ArgumentCaptor.forClass(
            UpdateWrapper.class
        );
        verify(leaseMapper).update(wrapperCaptor.capture());
        UpdateWrapper<DataRetentionCleanupLease> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
            .contains("lock_name")
            .contains("locked_until");
        assertThat(wrapper.getSqlSet())
            .contains("owner_id")
            .contains("locked_until")
            .contains("updated_at");
        assertThat(wrapper.getParamNameValuePairs().values())
            .contains("data_retention_cleanup", lease.ownerId());
    }

    @Test
    void acquireReturnsNullWhenLeaseIsStillOwned() {
        when(leaseMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        assertThat(store.acquire()).isNull();
    }

    @Test
    void releaseOnlyClearsOwnedLease() {
        DataRetentionCleanupLeaseStore.Lease lease = new DataRetentionCleanupLeaseStore.Lease("owner-1");

        store.release(lease);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<DataRetentionCleanupLease>> wrapperCaptor = ArgumentCaptor.forClass(
            UpdateWrapper.class
        );
        verify(leaseMapper).update(wrapperCaptor.capture());
        UpdateWrapper<DataRetentionCleanupLease> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
            .contains("lock_name")
            .contains("owner_id");
        assertThat(wrapper.getSqlSet())
            .contains("owner_id")
            .contains("locked_until")
            .contains("updated_at");
        assertThat(wrapper.getParamNameValuePairs().values())
            .contains("data_retention_cleanup", "owner-1");
    }

    @Test
    void releaseIgnoresMissingLease() {
        store.release(null);

        verify(leaseMapper, never()).update(any(UpdateWrapper.class));
    }
}
