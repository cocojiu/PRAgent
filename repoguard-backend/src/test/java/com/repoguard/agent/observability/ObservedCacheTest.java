package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;

class ObservedCacheTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(
        meterRegistry,
        new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
    );

    @Test
    void recordsDashboardCacheHitMissAndMutations() {
        Cache cache = observedCache("dashboardSummary", true);

        cache.put("summary", "value");
        assertThat(cache.get("summary").get()).isEqualTo("value");
        assertThat(cache.get("missing")).isNull();
        cache.evict("summary");
        cache.clear();

        assertThat(counter("repoguard.dashboard.cache.operation", "cache", "dashboardsummary", "operation", "put"))
            .isEqualTo(1.0);
        assertThat(counter("repoguard.dashboard.cache.access", "cache", "dashboardsummary", "result", "hit"))
            .isEqualTo(1.0);
        assertThat(counter("repoguard.dashboard.cache.access", "cache", "dashboardsummary", "result", "miss"))
            .isEqualTo(1.0);
        assertThat(counter("repoguard.dashboard.cache.operation", "cache", "dashboardsummary", "operation", "evict"))
            .isEqualTo(1.0);
        assertThat(counter("repoguard.dashboard.cache.operation", "cache", "dashboardsummary", "operation", "clear"))
            .isEqualTo(1.0);
    }

    @Test
    void recordsCallableMissOnlyWhenValueLoaderRuns() {
        Cache cache = observedCache("dashboardOverview", true);

        assertThat(cache.get("overview", () -> "generated")).isEqualTo("generated");
        assertThat(cache.get("overview", () -> "unused")).isEqualTo("generated");

        assertThat(counter("repoguard.dashboard.cache.access", "cache", "dashboardoverview", "result", "miss"))
            .isEqualTo(1.0);
        assertThat(counter("repoguard.dashboard.cache.access", "cache", "dashboardoverview", "result", "hit"))
            .isEqualTo(1.0);
    }

    @Test
    void recordsCallableMissFailureAndRethrows() {
        Cache cache = observedCache("dashboardOverview", true);

        assertThatThrownBy(() -> cache.get("overview", () -> {
            throw new IllegalStateException("loader failed");
        })).isInstanceOf(Cache.ValueRetrievalException.class);

        assertThat(counter("repoguard.dashboard.cache.access", "cache", "dashboardoverview", "result", "miss_failed"))
            .isEqualTo(1.0);
    }

    @Test
    void skipsMetricsForUnobservedCaches() {
        Cache cache = observedCache("githubOpenPullRequests", false);

        cache.put("repo", "prs");
        assertThat(cache.get("repo")).isNotNull();
        assertThat(meterRegistry.find("repoguard.dashboard.cache.access").counter()).isNull();
        assertThat(meterRegistry.find("repoguard.dashboard.cache.operation").counter()).isNull();
    }

    @Test
    void requiresDelegateAndMetrics() {
        Cache delegate = caffeineCache("dashboardOverview");

        assertThatThrownBy(() -> new ObservedCache(null, metrics, true))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("delegate");
        assertThatThrownBy(() -> new ObservedCache(delegate, null, false))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    private Cache observedCache(String name, boolean observeAccess) {
        return new ObservedCache(
            caffeineCache(name),
            metrics,
            observeAccess
        );
    }

    private Cache caffeineCache(String name) {
        return new CaffeineCache(name, Caffeine.newBuilder().recordStats().build(), false);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }
}
