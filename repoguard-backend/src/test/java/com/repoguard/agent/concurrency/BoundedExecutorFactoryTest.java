package com.repoguard.agent.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedExecutorFactoryTest {

    @Test
    void rejectsWhenWorkerAndQueueAreFullAndRecordsExecutorMetrics() throws Exception {
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        properties.setShutdownWaitSeconds(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new BoundedExecutorFactory(registry, properties).create("boundary-test", 1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });

            assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);

            assertThat(registry.get("repoguard.async.active").tag("executor", "boundary-test").gauge().value())
                .isEqualTo(1.0);
            assertThat(registry.get("repoguard.async.queued").tag("executor", "boundary-test").gauge().value())
                .isEqualTo(1.0);
            assertThat(registry.get("repoguard.async.rejected").tag("executor", "boundary-test").counter().count())
                .isEqualTo(1.0);
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }
}
