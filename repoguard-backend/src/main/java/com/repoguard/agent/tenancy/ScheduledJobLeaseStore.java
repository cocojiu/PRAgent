package com.repoguard.agent.tenancy;

import com.repoguard.agent.mapper.ScheduledJobLeaseMapper;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ScheduledJobLeaseStore {

    private static final Pattern JOB_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");

    private final ScheduledJobLeaseMapper leaseMapper;
    private final ScheduledJobLeaseProperties properties;

    public ScheduledJobLeaseStore(
        ScheduledJobLeaseMapper leaseMapper,
        ScheduledJobLeaseProperties properties
    ) {
        this.leaseMapper = Objects.requireNonNull(leaseMapper, "leaseMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease tryAcquireCurrentTenant(String jobName) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant scheduled job lease requires an active tenant context");
        }
        String normalizedJobName = requireJobName(jobName);
        return tryAcquire("tenant:" + tenantId + ":" + normalizedJobName, tenantId, normalizedJobName);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease tryAcquireGlobal(String jobName) {
        if (TenantContext.hasTenant()) {
            throw new IllegalStateException("Global scheduled job lease requires an empty tenant context");
        }
        String normalizedJobName = requireJobName(jobName);
        return tryAcquire("global:" + normalizedJobName, null, normalizedJobName);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Lease lease) {
        if (lease == null) {
            return;
        }
        leaseMapper.release(lease.scopeKey(), lease.ownerId());
    }

    private Lease tryAcquire(String scopeKey, Long tenantId, String jobName) {
        String ownerId = UUID.randomUUID().toString();
        leaseMapper.acquireOrCreate(
            scopeKey,
            tenantId,
            jobName,
            ownerId,
            properties.getLeaseSeconds()
        );
        return ownerId.equals(leaseMapper.selectOwner(scopeKey))
            ? new Lease(scopeKey, ownerId)
            : null;
    }

    private String requireJobName(String jobName) {
        String normalized = Objects.requireNonNull(jobName, "jobName").trim();
        if (!JOB_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("jobName must match " + JOB_NAME.pattern());
        }
        return normalized;
    }

    public record Lease(String scopeKey, String ownerId) {
        public Lease {
            Objects.requireNonNull(scopeKey, "scopeKey");
            Objects.requireNonNull(ownerId, "ownerId");
        }
    }
}
