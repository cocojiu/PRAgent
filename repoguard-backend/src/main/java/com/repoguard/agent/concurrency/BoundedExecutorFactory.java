package com.repoguard.agent.concurrency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import java.util.List;
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
            ScheduledJobLeaseContext.CapturedTask leaseTask = ScheduledJobLeaseContext.capture(command);
            try {
                super.execute(new TimedRunnable(TenantContext.wrap(leaseTask), leaseTask));
            } catch (RejectedExecutionException ex) {
                leaseTask.discard();
                rejected.increment();
                throw ex;
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> abandoned = super.shutdownNow();
            abandoned.forEach(runnable -> {
                if (runnable instanceof TimedRunnable timed) {
                    timed.discard();
                }
            });
            return abandoned;
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

    private record TimedRunnable(
        Runnable delegate,
        ScheduledJobLeaseContext.CapturedTask leaseTask,
        long submittedAtNanos
    ) implements Runnable {
        private TimedRunnable(Runnable delegate, ScheduledJobLeaseContext.CapturedTask leaseTask) {
            this(delegate, leaseTask, System.nanoTime());
        }

        @Override
        public void run() {
            delegate.run();
        }

        private void discard() {
            leaseTask.discard();
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
