package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DashboardSnapshotStoreTest {
    private TenantContext.Scope personalScope;
    @org.junit.jupiter.api.BeforeEach
    void personalEntry() { personalScope = TenantContext.withTenant(TenantContext.DEFAULT_TENANT_ID); }
    @org.junit.jupiter.api.AfterEach
    void clearPersonalEntry() { personalScope.close(); }

    @Test
    void invalidatedRunningRefreshCannotOverwriteNewDataOrReleaseTheNewRefresh() throws Exception {
        List<Runnable> refreshes = new ArrayList<>();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        DashboardSnapshotStore store = new DashboardSnapshotStore(refreshes::add, 10,
            java.time.Duration.ofSeconds(10), System::nanoTime, registry);
        store.getOrLoad("summary", () -> "seed");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.getOrLoad("summary", () -> {
            started.countDown();
            await(release);
            return "obsolete";
        });
        var workers = Executors.newSingleThreadExecutor();
        try {
            var old = workers.submit(refreshes.removeFirst());
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            store.evict("summary");
            assertThat(store.getOrLoad("summary", () -> "new")).isEqualTo("new");
            assertThat(store.getOrLoad("summary", () -> "newer")).isEqualTo("new");
            assertThat(refreshes).hasSize(1);
            release.countDown();
            old.get(1, TimeUnit.SECONDS);
            assertThat(store.getOrLoad("summary", () -> "duplicate")).isEqualTo("new");
            assertThat(refreshes).hasSize(1);
            assertThat(registry.get("repoguard.dashboard.snapshot.discarded_write").counter().count()).isEqualTo(1);
            refreshes.removeFirst().run();
            assertThat(store.getOrLoad("summary", () -> "next")).isEqualTo("newer");
        } finally {
            release.countDown();
            workers.shutdownNow();
            registry.close();
        }
    }

    @Test
    void expiredSnapshotIsReloadedAndAnOlderQueuedRefreshCannotReplaceIt() {
        AtomicLong clock = new AtomicLong();
        List<Runnable> refreshes = new ArrayList<>();
        DashboardSnapshotStore store = new DashboardSnapshotStore(refreshes::add, 10,
            java.time.Duration.ofSeconds(10), clock::get, null);
        assertThat(store.getOrLoad("summary", () -> "seed")).isEqualTo("seed");
        assertThat(store.getOrLoad("summary", () -> "obsolete")).isEqualTo("seed");
        clock.set(java.time.Duration.ofSeconds(10).toNanos());
        assertThat(store.getOrLoad("summary", () -> "fresh")).isEqualTo("fresh");
        refreshes.removeFirst().run();
        assertThat(store.getOrLoad("summary", () -> "next")).isEqualTo("fresh");
    }

    @Test
    void snapshotCapacityAndIdleExpiryAreBounded() {
        AtomicLong clock = new AtomicLong();
        DashboardSnapshotStore store = new DashboardSnapshotStore(Runnable::run, 2,
            java.time.Duration.ofSeconds(10), clock::get, null);
        for (int index = 0; index < 20; index++) {
            store.getOrLoad("summary-" + index, () -> "value");
        }
        assertThat(store.retainedKeys()).isLessThanOrEqualTo(2);
        clock.set(java.time.Duration.ofSeconds(21).toNanos());
        assertThat(store.retainedKeys()).isZero();
    }

    @Test
    void cacheEvictionDoesNotCancelOrDuplicateDurableBackgroundRefreshes() {
        List<Runnable> refreshes = new ArrayList<>();
        DashboardSnapshotStore store = new DashboardSnapshotStore(refreshes::add);
        AtomicInteger completed = new AtomicInteger();
        store.executeAsync("refresh", completed::incrementAndGet);
        store.evictByPrefix("");
        store.executeAsync("refresh", completed::incrementAndGet);
        assertThat(refreshes).hasSize(1);
        refreshes.removeFirst().run();
        assertThat(completed).hasValue(1);
        store.executeAsync("refresh", completed::incrementAndGet);
        assertThat(refreshes).hasSize(1);
    }

    @Test
    void coldMissLoadsOnlyOnceForSameKey() throws Exception {
        DashboardSnapshotStore store = new DashboardSnapshotStore(Runnable::run);
        var callers = Executors.newFixedThreadPool(2, task -> new Thread(TenantContext.wrap(task)));
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();

        try {
            var first = callers.submit(() -> store.getOrLoad("dashboardSummary:summary", () -> {
                loadCount.incrementAndGet();
                loaderStarted.countDown();
                await(releaseLoader);
                return "snapshot";
            }));

            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var second = callers.submit(() -> store.getOrLoad("dashboardSummary:summary", () -> {
                loadCount.incrementAndGet();
                return "duplicate";
            }));

            Thread.sleep(100);
            assertThat(loadCount).hasValue(1);
            assertThat(second.isDone()).isFalse();

            releaseLoader.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("snapshot");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("snapshot");
            assertThat(loadCount).hasValue(1);
        } finally {
            releaseLoader.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void rejectedRefreshSubmissionCanBeRetried() {
        AtomicInteger submissionCount = new AtomicInteger();
        Executor executor = task -> {
            if (submissionCount.incrementAndGet() == 1) {
                throw new RejectedExecutionException("executor busy");
            }
            task.run();
        };
        DashboardSnapshotStore store = new DashboardSnapshotStore(executor, 10,
            java.time.Duration.ofSeconds(120), System::nanoTime, null);
        AtomicInteger loadCount = new AtomicInteger();

        assertThat(store.getOrLoad("dashboardSummary:summary", () -> {
            loadCount.incrementAndGet();
            return "stale";
        })).isEqualTo("stale");

        assertThat(store.getOrLoad("dashboardSummary:summary", () -> {
            loadCount.incrementAndGet();
            return "not-loaded";
        })).isEqualTo("stale");
        assertThat(submissionCount).hasValue(1);
        assertThat(loadCount).hasValue(1);

        assertThat(store.getOrLoad("dashboardSummary:summary", () -> {
            loadCount.incrementAndGet();
            return "refreshed";
        })).isEqualTo("stale");
        assertThat(submissionCount).hasValue(2);
        assertThat(loadCount).hasValue(2);
    }

    @Test
    void sameSnapshotKeyAndEvictionAreIsolatedByTenant() {
        List<Runnable> refreshes = new ArrayList<>();
        DashboardSnapshotStore store = new DashboardSnapshotStore(refreshes::add);

        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            assertThat(store.getOrLoad("dashboardSummary:summary", () -> "tenant-two"))
                .isEqualTo("tenant-two");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            assertThat(store.getOrLoad("dashboardSummary:summary", () -> "tenant-three"))
                .isEqualTo("tenant-three");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            store.evict("dashboardSummary:summary");
            assertThat(store.getOrLoad("dashboardSummary:summary", () -> "tenant-two-refreshed"))
                .isEqualTo("tenant-two-refreshed");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            assertThat(store.getOrLoad("dashboardSummary:summary", () -> "wrong-tenant"))
                .isEqualTo("tenant-three");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
