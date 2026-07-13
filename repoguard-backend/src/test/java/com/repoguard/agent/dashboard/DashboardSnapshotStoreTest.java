package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DashboardSnapshotStoreTest {

    @Test
    void coldMissLoadsOnlyOnceForSameKey() throws Exception {
        DashboardSnapshotStore store = new DashboardSnapshotStore(Runnable::run);
        var callers = Executors.newFixedThreadPool(2);
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
        DashboardSnapshotStore store = new DashboardSnapshotStore(executor);
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
