package com.repoguard.agent.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecoveryWorkDispatcherTest {

    @Test
    void submitsNetworkWorkWithoutRunningItOnScannerThread() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicBoolean executed = new AtomicBoolean();
        RecoveryWorkDispatcher dispatcher = new RecoveryWorkDispatcher(submitted::set);

        assertThat(dispatcher.submit("notification_publish", () -> executed.set(true))).isTrue();
        assertThat(executed).isFalse();

        submitted.get().run();

        assertThat(executed).isTrue();
    }

    @Test
    void reportsBoundedExecutorRejectionForLaterDatabaseRetry() {
        RecoveryWorkDispatcher dispatcher = new RecoveryWorkDispatcher(command -> {
            throw new RejectedExecutionException("queue full");
        });

        assertThat(dispatcher.submit("notification_publish", () -> {
        })).isFalse();
    }
}
