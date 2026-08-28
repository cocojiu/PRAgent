package com.repoguard.agent.tenancy;

import java.util.Objects;

/**
 * Carries the authoritative tenant selected by authentication, a signed
 * webhook mapping, or a trusted background message. Client DTOs never set it.
 */
public final class TenantContext {

    public static final long DEFAULT_TENANT_ID = 1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Long currentTenantId() {
        return CURRENT.get();
    }

    public static long currentTenantIdOrDefault() {
        Long tenantId = CURRENT.get();
        return tenantId == null ? DEFAULT_TENANT_ID : tenantId;
    }

    public static boolean hasTenant() {
        return CURRENT.get() != null;
    }

    public static Scope withTenant(Long tenantId) {
        if (tenantId == null || tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        Long previous = CURRENT.get();
        CURRENT.set(tenantId);
        return new Scope(previous);
    }

    public static Runnable wrap(Runnable task) {
        Runnable requiredTask = Objects.requireNonNull(task, "task");
        Long captured = CURRENT.get();
        return () -> {
            Long previous = CURRENT.get();
            setCurrent(captured);
            try {
                requiredTask.run();
            } finally {
                setCurrent(previous);
            }
        };
    }

    private static void setCurrent(Long tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    public static final class Scope implements AutoCloseable {

        private final Long previous;
        private boolean closed;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(Objects.requireNonNull(previous));
            }
        }
    }
}
