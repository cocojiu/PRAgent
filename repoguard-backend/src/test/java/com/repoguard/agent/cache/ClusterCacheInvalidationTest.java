package com.repoguard.agent.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardSnapshotStore;
import com.repoguard.agent.mapper.ClusterCacheInvalidationMapper;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantScopedKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ClusterCacheInvalidationTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 8, 28);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 28, 12, 0);
    private static final List<String> TENANT_CACHE_NAMES = List.of(
        CacheNames.DASHBOARD_OVERVIEW,
        CacheNames.DASHBOARD_SUMMARY,
        CacheNames.DASHBOARD_REVIEW_TREND,
        CacheNames.DASHBOARD_RISK_DISTRIBUTION,
        CacheNames.DASHBOARD_RULES,
        CacheNames.DASHBOARD_HIGH_RISK_REVIEWS,
        CacheNames.DASHBOARD_LLM_QUALITY,
        CacheNames.REVIEW_TASK_LIST_SUMMARY,
        CacheNames.GITHUB_OPEN_PULL_REQUESTS,
        CacheNames.REVIEW_RULES
    );

    @AfterEach
    void tenantContextIsCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void databasePublisherIncrementsTheTenantVersionAndRecordsMetrics() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        when(mapper.increment(7L)).thenReturn(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DatabaseClusterCacheInvalidationPublisher publisher =
            new DatabaseClusterCacheInvalidationPublisher(mapper, registry);

        publisher.publish(7L, ClusterCacheInvalidationType.DASHBOARD_RULES, STAT_DATE);

        verify(mapper).increment(7L);
        assertThat(registry.get("repoguard.cache.invalidation")
            .tag("direction", "publish")
            .tag("type", "dashboard_rules")
            .tag("outcome", "version_incremented")
            .counter()
            .count()).isEqualTo(1.0d);
        registry.close();
    }

    @Test
    void databasePublisherFailsClosedWhenTheVersionWasNotIncremented() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DatabaseClusterCacheInvalidationPublisher publisher =
            new DatabaseClusterCacheInvalidationPublisher(mapper, registry);

        assertThatThrownBy(() -> publisher.publish(
            7L,
            ClusterCacheInvalidationType.REVIEW_RULES,
            null
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unexpected rows=0");
        registry.close();
    }

    @Test
    void versionChangeInvalidatesOnlyTheTargetTenantOnEveryReplica() {
        SimpleCacheManager originManager = cacheManager(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        SimpleCacheManager replicaManager = cacheManager(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        AtomicReference<PublishedVersion> published = new AtomicReference<>();
        ClusterCacheInvalidationPublisher publisher = (tenantId, type, statDate) ->
            published.set(new PublishedVersion(tenantId, type, statDate));
        CacheEvictionService origin = evictionService(originManager, null, publisher);
        CacheEvictionService replica = evictionService(replicaManager, null, null);
        TenantScopedKey tenantTwoKey = new TenantScopedKey(2L, "openPullRequests");
        TenantScopedKey tenantThreeKey = new TenantScopedKey(3L, "openPullRequests");
        putBoth(originManager, replicaManager, tenantTwoKey, "tenant-two");
        putBoth(originManager, replicaManager, tenantThreeKey, "tenant-three");

        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            origin.evictGithubOpenPullRequests();
        }

        assertThat(published.get()).isEqualTo(new PublishedVersion(
            2L,
            ClusterCacheInvalidationType.GITHUB_OPEN_PULL_REQUESTS,
            null
        ));
        replica.evictTenantLocal(published.get().tenantId());

        assertTenantEntry(originManager, tenantTwoKey, null);
        assertTenantEntry(replicaManager, tenantTwoKey, null);
        assertTenantEntry(originManager, tenantThreeKey, "tenant-three");
        assertTenantEntry(replicaManager, tenantThreeKey, "tenant-three");
    }

    @Test
    void versionIsIncrementedInsideTheTransactionBeforeLocalAfterCommitEviction() {
        SimpleCacheManager cacheManager = cacheManager(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        ClusterCacheInvalidationPublisher publisher = mock(ClusterCacheInvalidationPublisher.class);
        CacheEvictionService service = evictionService(cacheManager, null, publisher);
        TenantScopedKey key = new TenantScopedKey(7L, "openPullRequests");
        Cache cache = cacheManager.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        cache.put(key, "cached");

        TransactionSynchronizationManager.initSynchronization();
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            service.evictGithubOpenPullRequests();

            verify(publisher).publish(
                7L,
                ClusterCacheInvalidationType.GITHUB_OPEN_PULL_REQUESTS,
                null
            );
            assertThat(cache.get(key, String.class)).isEqualTo("cached");
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
            assertThat(cache.get(key)).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void tenantWideEvictionClearsBusinessCachesAndSnapshotsButKeepsGlobalHealthCache() {
        SimpleCacheManager cacheManager = cacheManager(
            java.util.stream.Stream.concat(
                TENANT_CACHE_NAMES.stream(),
                java.util.stream.Stream.of(CacheNames.MESSAGE_QUEUE_HEALTH)
            ).toArray(String[]::new)
        );
        DashboardSnapshotStore snapshotStore = new DashboardSnapshotStore(Runnable::run);
        ClusterCacheInvalidationPublisher publisher = mock(ClusterCacheInvalidationPublisher.class);
        CacheEvictionService service = evictionService(cacheManager, snapshotStore, publisher);
        TenantScopedKey tenantNineKey = new TenantScopedKey(9L, "business");
        TenantScopedKey tenantTenKey = new TenantScopedKey(10L, "business");
        for (String cacheName : TENANT_CACHE_NAMES) {
            Cache cache = cacheManager.getCache(cacheName);
            cache.put(tenantNineKey, "tenant-nine");
            cache.put(tenantTenKey, "tenant-ten");
        }
        Cache health = cacheManager.getCache(CacheNames.MESSAGE_QUEUE_HEALTH);
        health.put("health", "up");
        try (TenantContext.Scope _ = TenantContext.withTenant(9L)) {
            snapshotStore.getOrLoad("summary", () -> "tenant-nine-snapshot");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(10L)) {
            snapshotStore.getOrLoad("summary", () -> "tenant-ten-snapshot");
        }

        service.evictTenantLocal(9L);

        for (String cacheName : TENANT_CACHE_NAMES) {
            Cache cache = cacheManager.getCache(cacheName);
            assertThat(cache.get(tenantNineKey)).isNull();
            assertThat(cache.get(tenantTenKey, String.class)).isEqualTo("tenant-ten");
        }
        assertThat(health.get("health", String.class)).isEqualTo("up");
        try (TenantContext.Scope _ = TenantContext.withTenant(9L)) {
            assertThat(snapshotStore.getOrLoad("summary", () -> "tenant-nine-new"))
                .isEqualTo("tenant-nine-new");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(10L)) {
            assertThat(snapshotStore.getOrLoad("summary", () -> "tenant-ten-new"))
                .isEqualTo("tenant-ten-snapshot");
        }
        verifyNoInteractions(publisher);
    }

    @Test
    void startupPrimesVersionsWithoutEvictingEmptyCaches() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(2, 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectPage(0L, 2)).thenReturn(List.of(version(2L, 4L), version(3L, 8L)));
        when(mapper.selectPage(3L, 2)).thenReturn(List.of());
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);

        poller.initializeVersions();

        assertThat(poller.observedVersion(2L)).isEqualTo(4L);
        assertThat(poller.observedVersion(3L)).isEqualTo(8L);
        verifyNoInteractions(evictionService);
        registry.close();
    }

    @Test
    void pollerEvictsChangedAndNewTenantVersions() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(10, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectPage(0L, 10))
            .thenReturn(List.of(version(2L, 4L)))
            .thenReturn(List.of(version(2L, 5L), version(3L, 1L)));
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);
        poller.initializeVersions();

        poller.poll();

        verify(evictionService).evictTenantLocal(2L);
        verify(evictionService).evictTenantLocal(3L);
        assertThat(poller.observedVersion(2L)).isEqualTo(5L);
        assertThat(poller.observedVersion(3L)).isEqualTo(1L);
        assertThat(poller.scanAfterTenantId()).isZero();
        registry.close();
    }

    @Test
    void unchangedVersionDoesNotEvictAgain() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(10, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TenantCacheVersion version = version(2L, 4L);
        when(mapper.selectPage(0L, 10))
            .thenReturn(List.of(version))
            .thenReturn(List.of(version));
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);
        poller.initializeVersions();

        poller.poll();

        verify(evictionService, never()).evictTenantLocal(2L);
        registry.close();
    }

    @Test
    void failedEvictionLeavesTheVersionUnobservedForRetry() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(10, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectPage(0L, 10))
            .thenReturn(List.of())
            .thenReturn(List.of(version(7L, 1L)));
        doThrow(new IllegalStateException("cache unavailable"))
            .when(evictionService).evictTenantLocal(7L);
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);
        poller.initializeVersions();

        poller.poll();

        assertThat(poller.scanAfterTenantId()).isZero();
        assertThat(poller.observedVersion(7L)).isNull();
        assertThat(registry.get("repoguard.cache.invalidation")
            .tag("direction", "consume")
            .tag("type", "all")
            .tag("outcome", "failed")
            .counter()
            .count()).isEqualTo(1.0d);
        registry.close();
    }

    @Test
    void pollerRejectsNonIncreasingTenantOrder() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(10, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectPage(0L, 10))
            .thenReturn(List.of())
            .thenReturn(List.of(version(2L, 1L), version(2L, 2L)));
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);
        poller.initializeVersions();

        poller.poll();

        assertThat(poller.scanAfterTenantId()).isEqualTo(2L);
        assertThat(poller.observedVersion(2L)).isEqualTo(1L);
        verify(evictionService).evictTenantLocal(2L);
        registry.close();
    }

    @Test
    void pollerReportsBacklogWhenOnePollCannotScanTheConfiguredWindow() {
        ClusterCacheInvalidationMapper mapper = mock(ClusterCacheInvalidationMapper.class);
        CacheEvictionService evictionService = mock(CacheEvictionService.class);
        ClusterCacheInvalidationProperties properties = properties(1, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectPage(0L, 1))
            .thenReturn(List.of())
            .thenReturn(List.of(version(1L, 1L)));
        ClusterCacheInvalidationPoller poller =
            new ClusterCacheInvalidationPoller(mapper, evictionService, properties, registry);
        poller.initializeVersions();

        poller.poll();

        assertThat(poller.scanAfterTenantId()).isEqualTo(1L);
        assertThat(registry.get("repoguard.cache.invalidation")
            .tag("direction", "consume")
            .tag("type", "all")
            .tag("outcome", "backlog")
            .counter()
            .count()).isEqualTo(1.0d);
        registry.close();
    }

    @Test
    void propertiesAndVersionRecordEnforcePositiveBounds() {
        ClusterCacheInvalidationProperties properties = new ClusterCacheInvalidationProperties();
        properties.setEnabled(false);
        properties.setPollIntervalMs(250L);
        properties.setBatchSize(17);
        properties.setMaxBatchesPerPoll(4);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getPollIntervalMs()).isEqualTo(250L);
        assertThat(properties.getBatchSize()).isEqualTo(17);
        assertThat(properties.getMaxBatchesPerPoll()).isEqualTo(4);
        assertThatThrownBy(() -> version(0L, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(1L, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    private CacheEvictionService evictionService(
        SimpleCacheManager cacheManager,
        DashboardSnapshotStore snapshotStore,
        ClusterCacheInvalidationPublisher publisher
    ) {
        return new CacheEvictionService(
            cacheManager,
            () -> null,
            () -> snapshotStore,
            () -> null,
            () -> publisher
        );
    }

    private ClusterCacheInvalidationProperties properties(int batchSize, int maxBatches) {
        ClusterCacheInvalidationProperties properties = new ClusterCacheInvalidationProperties();
        properties.setBatchSize(batchSize);
        properties.setMaxBatchesPerPoll(maxBatches);
        return properties;
    }

    private TenantCacheVersion version(long tenantId, long cacheVersion) {
        return new TenantCacheVersion(tenantId, cacheVersion, UPDATED_AT);
    }

    private SimpleCacheManager cacheManager(String... names) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(java.util.Arrays.stream(names)
            .map(name -> new CaffeineCache(name, Caffeine.newBuilder().build()))
            .toList());
        manager.initializeCaches();
        return manager;
    }

    private void putBoth(
        SimpleCacheManager first,
        SimpleCacheManager second,
        TenantScopedKey key,
        String value
    ) {
        first.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS).put(key, value);
        second.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS).put(key, value);
    }

    private void assertTenantEntry(
        SimpleCacheManager manager,
        TenantScopedKey key,
        String expected
    ) {
        Cache cache = manager.getCache(CacheNames.GITHUB_OPEN_PULL_REQUESTS);
        assertThat(cache).isNotNull();
        assertThat(cache.get(key, String.class)).isEqualTo(expected);
    }

    private record PublishedVersion(
        long tenantId,
        ClusterCacheInvalidationType type,
        LocalDate statDate
    ) {
    }
}
