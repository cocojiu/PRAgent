package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DashboardSnapshotRefreshPolicyTest {
    @Test
    void freshHitsDoNotSubmitAndStaleHitsCoalesce() {
        var registry = new SimpleMeterRegistry();
        try (var _ = TenantContext.withTenant(1L)) {
            AtomicLong clock = new AtomicLong();
            List<Runnable> jobs = new ArrayList<>();
            var store = new DashboardSnapshotStore(jobs::add, 10, Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(1), clock::get, registry);
            assertThat(store.getOrLoad("a", () -> "initial")).isEqualTo("initial");
            for (int index = 0; index < 100; index++) store.getOrLoad("a", () -> "unexpected");
            assertThat(jobs).isEmpty();
            clock.set(Duration.ofSeconds(5).toNanos());
            for (int index = 0; index < 100; index++) store.getOrLoad("a", () -> "updated");
            assertThat(jobs).hasSize(1);
            jobs.removeFirst().run();
            assertThat(store.getOrLoad("a", () -> "unexpected")).isEqualTo("updated");
            assertThat(jobs).isEmpty();
            assertThat(registry.get("repoguard.dashboard.snapshot.refresh").tag("outcome", "submitted")
                .counter().count()).isEqualTo(1);
            store.evict("a");
            assertThat(store.getOrLoad("a", () -> "invalidated")).isEqualTo("invalidated");
        } finally { registry.close(); }
    }

    @Test
    void failedRefreshBacksOffAndHardExpiryStillLoadsSynchronously() {
        try (var _ = TenantContext.withTenant(1L)) {
            AtomicLong clock = new AtomicLong();
            AtomicInteger calls = new AtomicInteger();
            var store = new DashboardSnapshotStore(Runnable::run, 10, Duration.ofSeconds(3),
                Duration.ofSeconds(1), Duration.ofSeconds(10), clock::get, null);
            store.getOrLoad("a", () -> "seed");
            clock.set(Duration.ofSeconds(1).toNanos());
            store.getOrLoad("a", () -> { calls.incrementAndGet(); throw new IllegalStateException("offline"); });
            for (int index = 0; index < 100; index++) store.getOrLoad("a", () -> { calls.incrementAndGet(); return "bad"; });
            assertThat(calls).hasValue(1);
            clock.set(Duration.ofSeconds(3).toNanos());
            assertThat(store.getOrLoad("a", () -> "recovered")).isEqualTo("recovered");
            clock.set(Duration.ofSeconds(4).toNanos());
            store.getOrLoad("a", () -> { calls.incrementAndGet(); return "updated"; });
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void rejectedSubmissionUsesBackoffAndCanRecover() {
        try (var _ = TenantContext.withTenant(1L)) {
            AtomicLong clock = new AtomicLong();
            AtomicInteger submitted = new AtomicInteger();
            var store = new DashboardSnapshotStore(task -> {
                if (submitted.incrementAndGet() == 1) throw new RejectedExecutionException();
                task.run();
            }, 10, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(1), clock::get, null);
            store.getOrLoad("a", () -> "seed");
            clock.set(Duration.ofSeconds(1).toNanos());
            store.getOrLoad("a", () -> "ignored");
            store.getOrLoad("a", () -> "ignored");
            assertThat(submitted).hasValue(1);
            clock.set(Duration.ofSeconds(3).toNanos());
            store.getOrLoad("a", () -> "recovered");
            assertThat(submitted).hasValue(2);
            assertThat(store.getOrLoad("a", () -> "ignored")).isEqualTo("recovered");
        }
    }
}
