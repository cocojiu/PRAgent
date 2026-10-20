package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitRuntimeHealthProbeTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final AsyncExecutorProperties async = new AsyncExecutorProperties();

    private RabbitRuntimeHealthProbe probe(Executor executor) {
        properties.setHealthCheckTimeoutMs(100);
        async.setRabbitHealthFailureBackoffMillis(100);
        async.setRabbitHealthCacheMillis(100);
        return RabbitRuntimeHealthProbe.forTesting(template, properties, executor, async);
    }

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4));
    }

    @Test
    void repeatedCallerTimeoutsKeepOneRealProbeAndDoNotFillTheBoundedQueue() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var interrupted = new AtomicBoolean();
        when(template.execute(any())).thenAnswer(invocation -> {
            calls.incrementAndGet(); started.countDown();
            try { release.await(); } catch (InterruptedException ex) { interrupted.set(true); throw ex; }
            return true;
        });
        var executor = pool();
        try {
            var probe = probe(executor);
            assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                Thread.sleep(150); // Cross the configured 100ms failure backoff, with the supplier still blocked.
                assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
                assertThat(executor.getQueue()).isEmpty();
                assertThat(calls.get()).isEqualTo(1);
            }
            assertThat(interrupted).isFalse();
            release.countDown(); Thread.sleep(150);
            assertThat(probe.connectionStatus()).isEqualTo("CONNECTED");
            int recoveredCalls = calls.get();
            assertThat(probe.connectionStatus()).isEqualTo("CONNECTED");
            assertThat(calls.get()).isEqualTo(recoveredCalls);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedWaiterDoesNotCancelOrPoisonAnotherCallerSharingTheSupplier() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        when(template.execute(any())).thenAnswer(invocation -> { calls.incrementAndGet(); started.countDown(); release.await(); return true; });
        var executor = pool(); var status = new AtomicReference<String>(); var interrupted = new AtomicBoolean();
        var otherStatus = new AtomicReference<String>(); var probe = probe(executor);
        properties.setHealthCheckTimeoutMs(2000);
        var waiter = new Thread(() -> { status.set(probe.connectionStatus()); interrupted.set(Thread.currentThread().isInterrupted()); });
        var other = new Thread(() -> otherStatus.set(probe.connectionStatus()));
        try {
            waiter.start(); assertThat(started.await(2, TimeUnit.SECONDS)).isTrue(); waiter.interrupt(); waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse(); assertThat(status.get()).isEqualTo("UNKNOWN");
            assertThat(interrupted).isTrue(); assertThat(executor.getActiveCount()).isEqualTo(1);
            other.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (other.getState() != Thread.State.TIMED_WAITING && other.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
            assertThat(other.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            assertThat(executor.getQueue()).isEmpty(); assertThat(calls.get()).isEqualTo(1);
            release.countDown(); other.join(2000);
            assertThat(other.isAlive()).isFalse(); assertThat(otherStatus.get()).isEqualTo("CONNECTED");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            release.countDown(); waiter.interrupt(); other.interrupt(); waiter.join(2000);
            if (other.getState() != Thread.State.NEW) other.join(2000);
            executor.shutdownNow(); assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectionBacksOffAndAllowsRecoveryWithoutLeavingAnInFlightHandle() throws Exception {
        var submissions = new AtomicInteger();
        Executor executor = task -> { if (submissions.incrementAndGet() == 1) throw new java.util.concurrent.RejectedExecutionException(); task.run(); };
        when(template.execute(any())).thenReturn(true);
        var probe = probe(executor);
        assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
        assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
        assertThat(submissions.get()).isEqualTo(1);
        Thread.sleep(150);
        assertThat(probe.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(submissions.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void successfulProbeCachesTheActualConnectionState(boolean open) {
        when(template.execute(any())).thenReturn(open);
        var submissions = new AtomicInteger();
        var probe = probe(task -> { submissions.incrementAndGet(); task.run(); });
        async.setRabbitHealthCacheMillis(5000);
        assertThat(probe.connectionStatus()).isEqualTo(open ? "CONNECTED" : "DISCONNECTED");
        assertThat(probe.connectionStatus()).isEqualTo(open ? "CONNECTED" : "DISCONNECTED");
        assertThat(submissions.get()).isEqualTo(1);
    }

    @Test
    void supplierFailureUsesFailureBackoffAndRecovers() throws Exception {
        when(template.execute(any())).thenThrow(new IllegalStateException("Unavailable")).thenReturn(true);
        var probe = probe(Runnable::run);
        assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
        assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
        Thread.sleep(150);
        assertThat(probe.connectionStatus()).isEqualTo("CONNECTED");
    }

    @Test
    void realAmqpHandshakeStallDoesNotEnqueueAnotherConnectionProbe() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var accepted = new CountDownLatch(1); var socket = new AtomicReference<Socket>();
            var acceptor = Thread.ofVirtual().start(() -> {
                try { socket.set(server.accept()); accepted.countDown(); } catch (java.io.IOException ignored) { }
            });
            var rabbit = new com.rabbitmq.client.ConnectionFactory();
            rabbit.setHost(server.getInetAddress().getHostAddress()); rabbit.setPort(server.getLocalPort());
            rabbit.setAutomaticRecoveryEnabled(false); rabbit.setConnectionTimeout(1000); rabbit.setHandshakeTimeout(2000);
            var factory = new CachingConnectionFactory(rabbit); var executor = pool();
            try {
                properties.setHealthCheckTimeoutMs(100); async.setRabbitHealthFailureBackoffMillis(100);
                var probe = RabbitRuntimeHealthProbe.forTesting(new RabbitTemplate(factory), properties, executor, async);
                assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
                assertThat(accepted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150);
                assertThat(probe.connectionStatus()).isEqualTo("UNKNOWN");
                assertThat(executor.getActiveCount()).isEqualTo(1);
                assertThat(executor.getQueue()).isEmpty();
            } finally {
                if (socket.get() != null) socket.get().close();
                acceptor.interrupt(); acceptor.join(2000); executor.shutdownNow(); factory.destroy();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
