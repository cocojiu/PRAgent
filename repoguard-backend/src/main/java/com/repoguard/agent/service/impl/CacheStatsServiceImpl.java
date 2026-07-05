package com.repoguard.agent.service.impl;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.repoguard.agent.dto.CacheStatsItemDto;
import com.repoguard.agent.dto.CacheStatsResponse;
import com.repoguard.agent.service.CacheStatsService;
import java.util.Comparator;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

@Service
public class CacheStatsServiceImpl implements CacheStatsService {

    private final CacheManager cacheManager;

    public CacheStatsServiceImpl(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public CacheStatsResponse getStats() {
        return new CacheStatsResponse(
            cacheManager.getCacheNames().stream()
                .map(this::toStatsItem)
                .sorted(Comparator.comparing(CacheStatsItemDto::name))
                .toList()
        );
    }

    private CacheStatsItemDto toStatsItem(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        Object nativeCacheCandidate = cache == null ? null : cache.getNativeCache();
        if (nativeCacheCandidate instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache) {
            CacheStats stats = nativeCache.stats();
            return new CacheStatsItemDto(
                cacheName,
                nativeCache.estimatedSize(),
                stats.requestCount(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate(),
                stats.evictionCount()
            );
        }
        return new CacheStatsItemDto(cacheName, 0, 0, 0, 0, 0.0d, 0);
    }
}
