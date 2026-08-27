package com.repoguard.agent.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantScopedKey;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

class TenantCacheIsolationTest {

    @AfterEach
    void tenantContextIsCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void githubEvictionRemovesOnlyCurrentTenantEntries() {
        CaffeineCache cache = new CaffeineCache(
            CacheNames.GITHUB_OPEN_PULL_REQUESTS,
            Caffeine.newBuilder().build()
        );
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(cache));
        cacheManager.initializeCaches();
        CacheEvictionService evictionService = new CacheEvictionService(cacheManager);
        TenantScopedKey tenantTwoKey;
        TenantScopedKey tenantThreeKey;

        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            tenantTwoKey = TenantScopedKey.current("openPullRequests");
            cache.put(tenantTwoKey, "tenant-two");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            tenantThreeKey = TenantScopedKey.current("openPullRequests");
            cache.put(tenantThreeKey, "tenant-three");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            evictionService.evictGithubOpenPullRequests();
        }

        assertThat(cache.get(tenantTwoKey)).isNull();
        assertThat(cache.get(tenantThreeKey, String.class)).isEqualTo("tenant-three");
    }
}
