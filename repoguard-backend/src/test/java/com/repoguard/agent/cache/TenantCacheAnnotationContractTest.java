package com.repoguard.agent.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubPullRequestClientImpl;
import com.repoguard.agent.review.config.ReviewRuleConfigServiceImpl;
import com.repoguard.agent.service.impl.DashboardServiceImpl;
import com.repoguard.agent.service.impl.ReviewTaskQueryServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

class TenantCacheAnnotationContractTest {

    private static final List<Class<?>> TENANT_CACHED_TYPES = List.of(
        DashboardServiceImpl.class,
        ReviewTaskQueryServiceImpl.class,
        ReviewRuleConfigServiceImpl.class,
        GithubPullRequestClientImpl.class
    );

    @Test
    void everyTenantScopedCacheUsesTenantScopedKey() {
        List<Cacheable> cacheables = TENANT_CACHED_TYPES.stream()
            .flatMap(type -> List.of(type.getDeclaredMethods()).stream())
            .map(method -> method.getAnnotation(Cacheable.class))
            .filter(java.util.Objects::nonNull)
            .toList();

        assertThat(cacheables).hasSize(10);
        assertThat(cacheables)
            .allSatisfy(cacheable -> assertThat(cacheable.key()).contains("TenantScopedKey"));
    }
}
