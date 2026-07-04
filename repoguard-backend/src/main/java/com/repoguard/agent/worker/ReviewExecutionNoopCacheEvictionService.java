package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

class ReviewExecutionNoopCacheEvictionService extends CacheEvictionService {

    ReviewExecutionNoopCacheEvictionService() {
        super(new NoopCacheManager());
    }

    private static class NoopCacheManager implements CacheManager {

        @Override
        public Cache getCache(String name) {
            return null;
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return java.util.List.of();
        }
    }
}
