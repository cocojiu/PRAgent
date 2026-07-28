package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.DataRetentionProperties;
import com.repoguard.agent.entity.DataRetentionCleanupLease;
import com.repoguard.agent.mapper.DataRetentionCleanupLeaseMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataRetentionCleanupLeaseStoreTest {

    private final DataRetentionCleanupLeaseMapper leaseMapper = org.mockito.Mockito.mock(
        DataRetentionCleanupLeaseMapper.class
    );
    private final DataRetentionProperties properties = new DataRetentionProperties();
    private final DataRetentionCleanupLeaseStore store = new DataRetentionCleanupLeaseStore(leaseMapper, properties);

    @Test
    void constructorRejectsMissingMapper() {
        assertThatThrownBy(() -> new DataRetentionCleanupLeaseStore(null, properties))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("leaseMapper");
    }

    @Test
    void constructorRejectsMissingProperties() {
        assertThatThrownBy(() -> new DataRetentionCleanupLeaseStore(leaseMapper, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("properties");
    }

    @Test
    void acquireClaimsExpiredLeaseWithFreshOwner() {
        properties.setCleanupLeaseMinutes(7);
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
        assertThat(leaseDurationMinutes(wrapper)).isGreaterThanOrEqualTo(7);
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

    private long leaseDurationMinutes(UpdateWrapper<DataRetentionCleanupLease> wrapper) {
        List<LocalDateTime> dateTimes = wrapper.getParamNameValuePairs().values().stream()
            .filter(LocalDateTime.class::isInstance)
            .map(LocalDateTime.class::cast)
            .sorted(Comparator.naturalOrder())
            .toList();
        assertThat(dateTimes).hasSizeGreaterThanOrEqualTo(2);
        return java.time.Duration.between(dateTimes.getFirst(), dateTimes.getLast()).toMinutes();
    }
}
