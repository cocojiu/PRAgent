package com.repoguard.agent.cache;

import com.repoguard.agent.mapper.ClusterCacheInvalidationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "repoguard.cache-invalidation.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DatabaseClusterCacheInvalidationPublisher implements ClusterCacheInvalidationPublisher {

    private final ClusterCacheInvalidationMapper mapper;
    private final MeterRegistry meterRegistry;

    public DatabaseClusterCacheInvalidationPublisher(
        ClusterCacheInvalidationMapper mapper,
        MeterRegistry meterRegistry
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void publish(long tenantId, ClusterCacheInvalidationType type, LocalDate statDate) {
        ClusterCacheInvalidationType requiredType = Objects.requireNonNull(type, "type");
        int updated = mapper.increment(tenantId);
        if (updated < 1 || updated > 2) {
            throw new IllegalStateException(
                "Tenant cache version increment affected unexpected rows=" + updated
            );
        }
        meterRegistry.counter(
            "repoguard.cache.invalidation",
            "direction", "publish",
            "type", requiredType.name().toLowerCase(Locale.ROOT),
            "outcome", "version_incremented"
        ).increment();
    }
}
