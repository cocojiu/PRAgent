package com.repoguard.agent.concurrency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class BoundedExecutorFactory {

    private final MeterRegistry meterRegistry;
    private final AsyncExecutorProperties properties;

    public BoundedExecutorFactory(MeterRegistry meterRegistry, AsyncExecutorProperties properties) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    public ThreadPoolExecutor create(String name, int threads, int queueCapacity) {
        ObservedExecutor executor = new ObservedExecutor(
            name,
            properties.positive(threads),
            properties.positive(queueCapacity),
            properties.positive(properties.getShutdownWaitSeconds()),
            meterRegistry
        );
        meterRegistry.gauge("repoguard.async.active", java.util.List.of(tag(name)), executor, ThreadPoolExecutor::getActiveCount);
        meterRegistry.gauge("repoguard.async.queued", java.util.List.of(tag(name)), executor, value -> value.getQueue().size());
        meterRegistry.gauge("repoguard.async.oldest_age_seconds", java.util.List.of(tag(name)), executor, ObservedExecutor::oldestAgeSeconds);
        return executor;
    }

    private io.micrometer.core.instrument.Tag tag(String name) {
        return io.micrometer.core.instrument.Tag.of("executor", name);
    }

    private static final class ObservedExecutor extends ThreadPoolExecutor {

        private final Counter rejected;
        private final int shutdownWaitSeconds;

        private ObservedExecutor(
            String name,
            int threads,
            int queueCapacity,
            int shutdownWaitSeconds,
            MeterRegistry registry
        ) {
            super(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(name),
                new AbortPolicy()
            );
            this.shutdownWaitSeconds = shutdownWaitSeconds;
            this.rejected = Counter.builder("repoguard.async.rejected").tag("executor", name).register(registry);
        }

        @Override
        public void execute(Runnable command) {
            try {
                super.execute(new TimedRunnable(command));
            } catch (RejectedExecutionException ex) {
                rejected.increment();
                throw ex;
            }
        }

        private double oldestAgeSeconds() {
            Runnable queued = getQueue().peek();
            if (!(queued instanceof TimedRunnable timed)) {
                return 0;
            }
            return Math.max(0, System.nanoTime() - timed.submittedAtNanos()) / 1_000_000_000.0;
        }

        @Override
        public void shutdown() {
            super.shutdown();
            try {
                if (!awaitTermination(shutdownWaitSeconds, TimeUnit.SECONDS)) {
                    shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                shutdownNow();
            }
        }
    }

    private record TimedRunnable(Runnable delegate, long submittedAtNanos) implements Runnable {
        private TimedRunnable(Runnable delegate) {
            this(delegate, System.nanoTime());
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "repoguard-" + name + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
