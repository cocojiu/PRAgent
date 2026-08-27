package com.repoguard.agent.tenancy;

import java.util.Objects;

/**
 * Immutable process-local key that prevents caches, snapshots and in-flight
 * coordination from sharing values across tenants.
 */
public record TenantScopedKey(long tenantId, Object businessKey) {

    public TenantScopedKey {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        Objects.requireNonNull(businessKey, "businessKey");
    }

    public static TenantScopedKey current(Object businessKey) {
        return new TenantScopedKey(TenantContext.currentTenantIdOrDefault(), businessKey);
    }

    public boolean belongsTo(long expectedTenantId) {
        return tenantId == expectedTenantId;
    }
}
