package com.repoguard.agent.dto;

public record CacheStatsItemDto(
    String name,
    long estimatedSize,
    long requestCount,
    long hitCount,
    long missCount,
    double hitRate,
    long evictionCount
) {
}
