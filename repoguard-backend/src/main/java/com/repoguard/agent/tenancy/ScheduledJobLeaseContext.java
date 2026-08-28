package com.repoguard.agent.tenancy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduledJobLeaseContext {

    private static final ThreadLocal<ScheduledJobLeaseGuard> CURRENT = new ThreadLocal<>();

    private ScheduledJobLeaseContext() {
    }

    public static Scope withGuard(ScheduledJobLeaseGuard guard) {
        ScheduledJobLeaseGuard previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(guard, "guard"));
        return new Scope(previous);
    }

    public static void assertHeld() {
        ScheduledJobLeaseGuard guard = CURRENT.get();
        if (guard != null) {
            guard.assertHeld();
        }
    }

    public static Long currentFencingToken() {
        ScheduledJobLeaseGuard guard = CURRENT.get();
        return guard == null ? null : guard.fencingToken();
    }

    public static CapturedTask capture(Runnable task) {
        Runnable requiredTask = Objects.requireNonNull(task, "task");
        ScheduledJobLeaseGuard captured = CURRENT.get();
        if (captured != null) {
            captured.retain();
        }
        return new CapturedTask(captured, requiredTask);
    }

    public static final class CapturedTask implements Runnable {
        private final ScheduledJobLeaseGuard captured;
        private final Runnable task;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private CapturedTask(ScheduledJobLeaseGuard captured, Runnable task) {
            this.captured = captured;
            this.task = task;
        }

        @Override
        public void run() {
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("Captured scheduled lease task already consumed");
            }
            ScheduledJobLeaseGuard previous = CURRENT.get();
            setCurrent(captured);
            Thread thread = Thread.currentThread();
            if (captured != null) {
                captured.enter(thread);
            }
            try {
                if (captured != null) {
                    captured.assertHeld();
                }
                task.run();
                if (captured != null) {
                    captured.assertHeld();
                }
            } finally {
                if (captured != null) {
                    captured.exit(thread);
                    captured.releaseReference();
                }
                setCurrent(previous);
            }
        }

        public void discard() {
            if (consumed.compareAndSet(false, true) && captured != null) {
                captured.releaseReference();
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final ScheduledJobLeaseGuard previous;
        private boolean closed;

        private Scope(ScheduledJobLeaseGuard previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                setCurrent(previous);
            }
        }
    }

    private static void setCurrent(ScheduledJobLeaseGuard guard) {
        if (guard == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(guard);
        }
    }
}
