package com.repoguard.agent.tenancy;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit, audited escape hatch for the small set of platform operations that
 * must access tenant-owned tables across all tenants.
 */
public final class PlatformTenantScope implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformTenantScope.class);
    private static final ThreadLocal<String> CURRENT_OPERATION = new ThreadLocal<>();

    private final Thread ownerThread;
    private final String operation;
    private boolean closed;

    private PlatformTenantScope(String operation) {
        this.ownerThread = Thread.currentThread();
        this.operation = operation;
    }

    public static PlatformTenantScope open(String operation) {
        String normalizedOperation = Objects.requireNonNull(operation, "operation").trim();
        if (normalizedOperation.isEmpty()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (TenantContext.hasTenant()) {
            throw new IllegalStateException("Platform tenant scope requires an empty tenant context");
        }
        if (CURRENT_OPERATION.get() != null) {
            throw new IllegalStateException("Nested platform tenant scope is not allowed");
        }
        CURRENT_OPERATION.set(normalizedOperation);
        LOGGER.info("Platform tenant scope opened operation={} result=opened", normalizedOperation);
        return new PlatformTenantScope(normalizedOperation);
    }

    public static boolean isActive() {
        return CURRENT_OPERATION.get() != null;
    }

    public static String currentOperation() {
        return CURRENT_OPERATION.get();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Platform tenant scope must close on its owner thread");
        }
        closed = true;
        CURRENT_OPERATION.remove();
        LOGGER.info("Platform tenant scope closed operation={} result=closed", operation);
    }
}
