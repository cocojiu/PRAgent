package com.repoguard.agent.cache;

import java.time.LocalDateTime;
import java.util.Objects;

public record TenantCacheVersion(
    long tenantId,
    long cacheVersion,
    LocalDateTime updatedAt
) {

    public TenantCacheVersion {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (cacheVersion < 1) {
            throw new IllegalArgumentException("cacheVersion must be positive");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
