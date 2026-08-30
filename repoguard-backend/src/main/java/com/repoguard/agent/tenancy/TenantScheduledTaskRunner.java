package com.repoguard.agent.tenancy;

import com.repoguard.agent.mapper.TenantCatalogMapper;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TenantScheduledTaskRunner {

    static final int ACTIVE_TENANT_PAGE_SIZE = 256;

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantScheduledTaskRunner.class);

    private final TenantCatalogMapper tenantCatalogMapper;
    private final TenantProperties properties;
    private final ScheduledJobLeaseGuardFactory leaseGuardFactory;

    public TenantScheduledTaskRunner(
        TenantCatalogMapper tenantCatalogMapper,
        TenantProperties properties,
        ScheduledJobLeaseGuardFactory leaseGuardFactory
    ) {
        this.tenantCatalogMapper = Objects.requireNonNull(tenantCatalogMapper, "tenantCatalogMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.leaseGuardFactory = Objects.requireNonNull(leaseGuardFactory, "leaseGuardFactory");
    }

    public TenantRunSummary runForEachActiveTenant(String operation, Runnable task) {
        String normalizedOperation = requireOperation(operation);
        Runnable requiredTask = Objects.requireNonNull(task, "task");
        if (TenantContext.hasTenant()) {
            throw new IllegalStateException(
                "Tenant scheduled task runner requires an empty tenant context operation=" + normalizedOperation
            );
        }
        if (!properties.isEnabled()) {
            return runDefaultTenant(normalizedOperation, requiredTask);
        }

        MutableSummary summary = new MutableSummary();
        long afterTenantId = 0L;
        while (true) {
            List<Long> tenantIds = tenantCatalogMapper.selectActiveTenantIdsAfter(
                afterTenantId,
                ACTIVE_TENANT_PAGE_SIZE
            );
            if (tenantIds.isEmpty()) {
                break;
            }
            for (Long tenantId : tenantIds) {
                if (tenantId == null || tenantId <= afterTenantId) {
                    throw new IllegalStateException(
                        "Active tenant catalog returned a non-increasing id operation=" + normalizedOperation
                    );
                }
                runTenant(normalizedOperation, tenantId, requiredTask, summary);
                afterTenantId = tenantId;
            }
        }
        if (summary.attempted == 0) {
            LOGGER.warn("Tenant scheduled task skipped because no active tenant exists operation={}", normalizedOperation);
        }
        return summary.toValue();
    }

    public boolean runGlobal(String operation, Runnable task) {
        String normalizedOperation = requireOperation(operation);
        Runnable requiredTask = Objects.requireNonNull(task, "task");
        if (TenantContext.hasTenant()) {
            throw new IllegalStateException(
                "Global scheduled task runner requires an empty tenant context operation=" + normalizedOperation
            );
        }
        ScheduledJobLeaseGuard guard = leaseGuardFactory.tryAcquireGlobal(normalizedOperation);
        if (guard == null) {
            LOGGER.debug("Global scheduled task skipped because lease is owned operation={}", normalizedOperation);
            return false;
        }
        try (
            guard;
            ScheduledJobLeaseContext.Scope _ = ScheduledJobLeaseContext.withGuard(guard);
            PlatformTenantScope _ = PlatformTenantScope.open(normalizedOperation)
        ) {
            guard.assertHeld();
            requiredTask.run();
            guard.assertHeld();
            return true;
        }
    }

    private TenantRunSummary runDefaultTenant(String operation, Runnable task) {
        MutableSummary summary = new MutableSummary();
        runTenant(operation, TenantContext.DEFAULT_TENANT_ID, task, summary);
        return summary.toValue();
    }

    private void runTenant(
        String operation,
        long tenantId,
        Runnable task,
        MutableSummary summary
    ) {
        summary.attempted++;
        try (TenantContext.Scope _ = TenantContext.withTenant(tenantId)) {
            ScheduledJobLeaseGuard guard = leaseGuardFactory.tryAcquireCurrentTenant(operation);
            if (guard == null) {
                summary.skipped++;
                LOGGER.debug(
                    "Tenant scheduled task skipped because lease is owned operation={} tenantId={}",
                    operation,
                    tenantId
                );
                return;
            }
            try (guard; ScheduledJobLeaseContext.Scope _ = ScheduledJobLeaseContext.withGuard(guard)) {
                guard.assertHeld();
                task.run();
                guard.assertHeld();
                summary.succeeded++;
            }
        } catch (RuntimeException exception) {
            summary.failed++;
            LOGGER.warn(
                "Tenant scheduled task failed operation={} tenantId={} result=failed",
                operation,
                tenantId,
                exception
            );
        }
    }

    private String requireOperation(String operation) {
        String normalized = Objects.requireNonNull(operation, "operation").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return normalized;
    }

    public record TenantRunSummary(int attempted, int succeeded, int failed, int skipped) {
    }

    private static final class MutableSummary {
        private int attempted;
        private int succeeded;
        private int failed;
        private int skipped;

        private TenantRunSummary toValue() {
            return new TenantRunSummary(attempted, succeeded, failed, skipped);
        }
    }
}
