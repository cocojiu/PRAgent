package com.repoguard.agent.tenancy;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobLeaseGuardFactory {

    private final ScheduledJobLeaseStore store;
    private final ScheduledJobLeaseProperties properties;
    private final ScheduledExecutorService heartbeatExecutor;

    public ScheduledJobLeaseGuardFactory(
        ScheduledJobLeaseStore store,
        ScheduledJobLeaseProperties properties,
        @Qualifier(ScheduledJobLeaseExecutorConfig.HEARTBEAT_EXECUTOR)
        ScheduledExecutorService heartbeatExecutor
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
    }

    public ScheduledJobLeaseGuard tryAcquireCurrentTenant(String jobName) {
        return guard(store.tryAcquireCurrentTenant(jobName));
    }

    public ScheduledJobLeaseGuard tryAcquireGlobal(String jobName) {
        return guard(store.tryAcquireGlobal(jobName));
    }

    private ScheduledJobLeaseGuard guard(ScheduledJobLeaseStore.Lease lease) {
        if (lease == null) {
            return null;
        }
        return new ScheduledJobLeaseGuard(
            lease,
            store,
            heartbeatExecutor,
            properties.getHeartbeatSeconds()
        );
    }
}
