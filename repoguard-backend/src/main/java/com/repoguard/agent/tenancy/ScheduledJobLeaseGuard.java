package com.repoguard.agent.tenancy;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScheduledJobLeaseGuard implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledJobLeaseGuard.class);

    private final ScheduledJobLeaseStore.Lease lease;
    private final ScheduledJobLeaseStore store;
    private final AtomicInteger references = new AtomicInteger(1);
    private final AtomicBoolean rootClosed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> heartbeat;

    ScheduledJobLeaseGuard(
        ScheduledJobLeaseStore.Lease lease,
        ScheduledJobLeaseStore store,
        ScheduledExecutorService heartbeatExecutor,
        long heartbeatSeconds
    ) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        if (heartbeatSeconds < 1) {
            throw new IllegalArgumentException("heartbeatSeconds must be positive");
        }
        try {
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                TenantContext.wrap(this::renewSafely),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS
            );
        } catch (RuntimeException exception) {
            store.release(lease);
            throw exception;
        }
    }

    public long fencingToken() {
        return lease.fencingToken();
    }

    public String scopeKey() {
        return lease.scopeKey();
    }

    void retain() {
        while (true) {
            int current = references.get();
            if (current < 1 || lost.get() || finished.get()) {
                throw lostException(null);
            }
            if (references.compareAndSet(current, Math.addExact(current, 1))) {
                return;
            }
        }
    }

    void releaseReference() {
        int remaining = references.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("Scheduled lease reference count underflow scope=" + lease.scopeKey());
        }
        if (remaining == 0) {
            finish();
        }
    }

    void enter(Thread thread) {
        activeThreads.add(Objects.requireNonNull(thread, "thread"));
    }

    void exit(Thread thread) {
        activeThreads.remove(thread);
    }

    public void assertHeld() {
        if (lost.get() || finished.get()) {
            throw lostException(null);
        }
        try {
            if (!store.isHeld(lease)) {
                markLost(null);
                throw lostException(null);
            }
        } catch (ScheduledJobLeaseLostException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            markLost(exception);
            throw lostException(exception);
        }
    }

    private void renewSafely() {
        if (finished.get() || lost.get()) {
            return;
        }
        try {
            if (!store.renew(lease)) {
                markLost(null);
            }
        } catch (RuntimeException exception) {
            markLost(exception);
        }
    }

    private void markLost(Throwable cause) {
        if (!lost.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> currentHeartbeat = heartbeat;
        if (currentHeartbeat != null) {
            currentHeartbeat.cancel(false);
        }
        Thread current = Thread.currentThread();
        activeThreads.stream()
            .filter(thread -> thread != current)
            .forEach(Thread::interrupt);
        if (cause == null) {
            LOGGER.error(
                "Scheduled job lease lost scope={} fencingToken={}",
                lease.scopeKey(),
                lease.fencingToken()
            );
        } else {
            LOGGER.error(
                "Scheduled job lease heartbeat failed closed scope={} fencingToken={}",
                lease.scopeKey(),
                lease.fencingToken(),
                cause
            );
        }
    }

    private ScheduledJobLeaseLostException lostException(Throwable cause) {
        String message = "Scheduled job lease is no longer held scope=" + lease.scopeKey()
            + " fencingToken=" + lease.fencingToken();
        return cause == null
            ? new ScheduledJobLeaseLostException(message)
            : new ScheduledJobLeaseLostException(message, cause);
    }

    @Override
    public void close() {
        if (rootClosed.compareAndSet(false, true)) {
            releaseReference();
        }
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> currentHeartbeat = heartbeat;
        if (currentHeartbeat != null) {
            currentHeartbeat.cancel(false);
        }
        try {
            store.release(lease);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Scheduled job lease release failed scope={} fencingToken={}",
                lease.scopeKey(),
                lease.fencingToken(),
                exception
            );
        }
    }
}
