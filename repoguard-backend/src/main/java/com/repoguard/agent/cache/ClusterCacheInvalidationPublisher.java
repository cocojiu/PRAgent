package com.repoguard.agent.cache;

import java.time.LocalDate;

@FunctionalInterface
public interface ClusterCacheInvalidationPublisher {

    void publish(long tenantId, ClusterCacheInvalidationType type, LocalDate statDate);
}
