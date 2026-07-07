package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.DataRetentionCleanupLease;
import com.repoguard.agent.mapper.DataRetentionCleanupLeaseMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataRetentionCleanupLeaseStore {

    private static final String LOCK_NAME = "data_retention_cleanup";
    private static final long LEASE_MINUTES = 30;

    private final DataRetentionCleanupLeaseMapper leaseMapper;

    public DataRetentionCleanupLeaseStore(DataRetentionCleanupLeaseMapper leaseMapper) {
        this.leaseMapper = Objects.requireNonNull(leaseMapper, "leaseMapper");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease acquire() {
        LocalDateTime now = LocalDateTime.now();
        String ownerId = UUID.randomUUID().toString();
        int updated = leaseMapper.update(
            new UpdateWrapper<DataRetentionCleanupLease>()
                .eq("lock_name", LOCK_NAME)
                .le("locked_until", now)
                .set("owner_id", ownerId)
                .set("locked_until", now.plusMinutes(LEASE_MINUTES))
                .set("updated_at", now)
        );
        return updated > 0 ? new Lease(ownerId) : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Lease lease) {
        if (lease == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        leaseMapper.update(
            new UpdateWrapper<DataRetentionCleanupLease>()
                .eq("lock_name", LOCK_NAME)
                .eq("owner_id", lease.ownerId())
                .set("owner_id", null)
                .set("locked_until", now)
                .set("updated_at", now)
        );
    }

    public record Lease(String ownerId) {
        public Lease {
            Objects.requireNonNull(ownerId, "ownerId");
        }
    }
}
