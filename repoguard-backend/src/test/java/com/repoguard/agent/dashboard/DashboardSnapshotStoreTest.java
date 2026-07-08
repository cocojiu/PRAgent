package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
