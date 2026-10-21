package com.repoguard.agent.cache;

import com.repoguard.agent.config.ReplicaRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.mapper.ClusterCacheInvalidationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs on every application replica. This task intentionally does not use the
 * cross-replica scheduled-job lease because every local cache must consume the
 * same durable per-tenant version table.
 */
@Component
@ReplicaRuntimeEnabled
@EnterpriseEditionEnabled
@ConditionalOnProperty(
    name = "repoguard.cache-invalidation.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ClusterCacheInvalidationPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterCacheInvalidationPoller.class);

    private final ClusterCacheInvalidationMapper mapper;
    private final CacheEvictionService cacheEvictionService;
    private final ClusterCacheInvalidationProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<Long, Long> observedVersions = new ConcurrentHashMap<>();
    private long scanAfterTenantId;
    private long scanStartedAtNanos;

    public ClusterCacheInvalidationPoller(
        ClusterCacheInvalidationMapper mapper,
        CacheEvictionService cacheEvictionService,
        ClusterCacheInvalidationProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        meterRegistry.gauge("repoguard.cache.invalidation.observed_tenants", observedVersions, Map::size);
    }

    @PostConstruct
    public synchronized void initializeVersions() {
        long startedAt = System.nanoTime();
        long afterTenantId = 0L;
        try {
            while (true) {
                List<TenantCacheVersion> versions = mapper.selectPage(
                    afterTenantId,
                    properties.getBatchSize()
                );
                recordScannedRows(versions.size(), "initialize");
                for (TenantCacheVersion version : versions) {
                    if (version.tenantId() <= afterTenantId) {
                        throw new IllegalStateException(
                            "Tenant cache initialization order must increase previous=" + afterTenantId
                                + " current=" + version.tenantId()
                        );
                    }
                    observedVersions.put(version.tenantId(), version.cacheVersion());
                    afterTenantId = version.tenantId();
                }
                if (versions.size() < properties.getBatchSize()) {
                    return;
                }
            }
        } finally {
            meterRegistry.timer("repoguard.cache.invalidation.initialize.duration")
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    @Scheduled(fixedDelayString = "${repoguard.cache-invalidation.poll-interval-ms:1000}")
    public synchronized void poll() {
        if (scanStartedAtNanos == 0L) scanStartedAtNanos = System.nanoTime();
        try {
            for (int batch = 0; batch < properties.getMaxBatchesPerPoll(); batch++) {
                List<TenantCacheVersion> versions = mapper.selectPage(
                    scanAfterTenantId,
                    properties.getBatchSize()
                );
                recordScannedRows(versions.size(), "poll");
                if (versions.isEmpty()) {
                    completeScan();
                    return;
                }
                for (TenantCacheVersion version : versions) {
                    apply(version);
                }
                if (versions.size() < properties.getBatchSize()) {
                    completeScan();
                    return;
                }
            }
            meterRegistry.counter(
                "repoguard.cache.invalidation",
                "direction", "consume",
                "type", "all",
                "outcome", "backlog"
            ).increment();
            LOGGER.debug(
                "Cluster cache version scan reached batch limit scanAfterTenantId={} maxBatches={}",
                scanAfterTenantId,
                properties.getMaxBatchesPerPoll()
            );
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                "repoguard.cache.invalidation",
                "direction", "consume",
                "type", "all",
                "outcome", "failed"
            ).increment();
            LOGGER.error(
                "Cluster cache version scan failed scanAfterTenantId={}",
                scanAfterTenantId,
                exception
            );
        }
    }

    long scanAfterTenantId() {
        return scanAfterTenantId;
    }

    Long observedVersion(long tenantId) {
        return observedVersions.get(tenantId);
    }

    private void recordScannedRows(int rows, String phase) {
        meterRegistry.counter("repoguard.cache.invalidation.scanned_rows", "phase", phase).increment(rows);
    }

    private void completeScan() {
        meterRegistry.timer("repoguard.cache.invalidation.scan.duration")
            .record(Duration.ofNanos(System.nanoTime() - scanStartedAtNanos));
        scanAfterTenantId = 0L;
        scanStartedAtNanos = 0L;
    }

    private void apply(TenantCacheVersion version) {
        if (version.tenantId() <= scanAfterTenantId) {
            throw new IllegalStateException(
                "Tenant cache scan order must increase previous=" + scanAfterTenantId
                    + " current=" + version.tenantId()
            );
        }
        Long previous = observedVersions.get(version.tenantId());
        if (previous == null || previous.longValue() != version.cacheVersion()) {
            cacheEvictionService.evictTenantLocal(version.tenantId());
            observedVersions.put(version.tenantId(), version.cacheVersion());
            meterRegistry.counter(
                "repoguard.cache.invalidation",
                "direction", "consume",
                "type", "tenant_version",
                "outcome", "applied"
            ).increment();
        }
        scanAfterTenantId = version.tenantId();
    }
}
