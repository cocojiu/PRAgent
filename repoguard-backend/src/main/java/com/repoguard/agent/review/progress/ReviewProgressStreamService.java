package com.repoguard.agent.review.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantResolutionService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded, coalesced invalidations sourced from durable task/timeline state, never worker transactions. */
@Service
@ApiRuntimeEnabled
@EnableConfigurationProperties(ReviewProgressStreamService.Limits.class)
@ConditionalOnProperty(name = "repoguard.review.progress-stream-enabled", havingValue = "true")
public class ReviewProgressStreamService {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "SUPERSEDED",
        "PENDING_HUMAN_REVIEW", "APPROVED", "CHANGES_REQUESTED", "REJECTED");
    private final ReviewTaskMapper tasks;
    private final ReviewTimelineMapper timelines;
    private final AuthAccountCache accounts;
    private final TenantProperties tenantProperties;
    private final TenantResolutionService tenantResolution;
    private final Limits limits;
    private final Map<TaskKey, SampleGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService deliveries;
    private MeterRegistry metrics;

    @Autowired(required = false)
    void recordMetrics(MeterRegistry registry) {
        metrics = registry;
        registry.gauge("repoguard.review.progress.connections", connections, Map::size);
        registry.gauge("repoguard.review.progress.groups", groups, Map::size);
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        return new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "review-progress-stream");
        thread.setDaemon(true);
        return thread;
        });
    }

    public ReviewProgressStreamService(ReviewTaskMapper tasks, ReviewTimelineMapper timelines,
        AuthAccountCache accounts, TenantProperties tenantProperties, TenantResolutionService tenantResolution) {
        this(tasks, timelines, accounts, tenantProperties, tenantResolution, new Limits());
    }

    @Autowired
    public ReviewProgressStreamService(ReviewTaskMapper tasks, ReviewTimelineMapper timelines,
        AuthAccountCache accounts, TenantProperties tenantProperties, TenantResolutionService tenantResolution, Limits limits) {
        this(tasks, timelines, accounts, tenantProperties, tenantResolution, limits, newScheduler());
    }

    ReviewProgressStreamService(ReviewTaskMapper tasks, ReviewTimelineMapper timelines,
        AuthAccountCache accounts, TenantProperties tenantProperties, TenantResolutionService tenantResolution,
        Limits limits, ScheduledThreadPoolExecutor scheduler) {
        this.scheduler = scheduler;
        var sender = new ThreadPoolExecutor(limits.maxConnections, limits.maxConnections, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.maxConnections), Thread.ofVirtual().name("review-progress-delivery-", 0).factory());
        sender.allowCoreThreadTimeOut(true);
        deliveries = sender;
        this.limits = limits;
        this.tasks = tasks;
        this.timelines = timelines;
        this.accounts = accounts;
        this.tenantProperties = tenantProperties;
        this.tenantResolution = tenantResolution;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public SseEmitter open(Long taskId, AuthenticatedPrincipal principal, Long lastEventId) {
        if (scheduler.isShutdown()) throw new IllegalStateException("Progress stream is closed");
        long tenantId = tenantProperties.currentTenantId();
        authorize(principal, tenantId);
        task(taskId); // Synchronous 404 and tenant check before HTTP headers are committed.
        return register(taskId, principal, lastEventId, tenantId);
    }

    private synchronized SseEmitter register(Long taskId, AuthenticatedPrincipal principal, Long lastEventId, long tenantId) {
        if (scheduler.isShutdown()) throw new IllegalStateException("Progress stream is closed");
        long perUser = connections.values().stream().filter(c -> c.principal.id().equals(principal.id())).count();
        if (connections.size() >= limits.maxConnections || perUser >= limits.perUser) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Progress stream limit reached");
        }
        String key = UUID.randomUUID().toString();
        SseEmitter emitter = newEmitter();
        Connection connection = new Connection(emitter, taskId, principal, tenantId,
            lastEventId == null ? 0L : lastEventId);
        connections.put(key, connection);
        TaskKey taskKey = new TaskKey(tenantId, taskId);
        SampleGroup group = groups.computeIfAbsent(taskKey, ignored -> new SampleGroup());
        group.subscribers.put(key, connection);
        Runnable release = () -> release(taskKey, group, key);
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(ignored -> release.run());
        if (group.future == null) {
            group.nextDue = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.sampleIntervalMs);
            group.future = scheduler.scheduleWithFixedDelay(() -> tick(taskKey, group),
                limits.sampleIntervalMs, limits.sampleIntervalMs, TimeUnit.MILLISECONDS);
        }
        connection.initialSample = scheduler.schedule(() -> tick(taskKey, group, false), 0, TimeUnit.MILLISECONDS);
        return emitter;
    }

    SseEmitter newEmitter() { return new SseEmitter(120_000L); }

    private void authorize(AuthenticatedPrincipal principal, long tenantId) {
        if (principal.id() == null || principal.id() <= 0 || principal.expiresAt() <= Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User session required");
        }
        var account = accounts.findById(principal.id());
        if (account == null || !"ACTIVE".equals(account.getStatus())
            || (account.getSessionVersion() == null ? 0 : account.getSessionVersion()) != principal.sessionVersion()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User session is no longer valid");
        }
        if (tenantProperties.isEnabled()) {
            tenantResolution.resolve(principal.id(), tenantId, null);
        }
    }

    private ReviewTask task(Long id) {
        ReviewTask task = tasks.selectOne(new LambdaQueryWrapper<ReviewTask>()
            .select(ReviewTask::getId, ReviewTask::getStatus, ReviewTask::getLlmStatus, ReviewTask::getCurrentAttemptId)
            .eq(ReviewTask::getId, id));
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task is missing or archived");
        }
        return task;
    }

    private synchronized void release(TaskKey taskKey, SampleGroup group, String key) {
        Connection removed = connections.remove(key);
        if (removed != null) {
            removed.closed = true;
            removed.pending.set(null);
            Thread sendingThread = removed.sendingThread;
            if (sendingThread != null && sendingThread != Thread.currentThread()) sendingThread.interrupt();
        }
        if (removed != null && removed.initialSample != null) removed.initialSample.cancel(false);
        group.subscribers.remove(key);
        if (group.subscribers.isEmpty() && groups.remove(taskKey, group) && group.future != null) {
            group.future.cancel(false);
        }
    }

    void tick(TaskKey key, SampleGroup group) { tick(key, group, true); }

    private void tick(TaskKey key, SampleGroup group, boolean heartbeat) {
        long startedAt = System.nanoTime();
        if (heartbeat && metrics != null) metrics.timer("repoguard.review.progress.sample.lag")
            .record(Math.max(0L, startedAt - group.nextDue), TimeUnit.NANOSECONDS);
        try {
            synchronized (group) {
                // Authorization belongs to each subscriber; sampling belongs to the tenant/task.
                List<Map.Entry<String, Connection>> eligible = group.subscribers.entrySet().stream().filter(entry -> {
                    Connection connection = entry.getValue();
                    try (var _ = TenantContext.withTenant(key.tenantId)) {
                        authorize(connection.principal, key.tenantId);
                        if (System.nanoTime() - connection.startedAt < TimeUnit.SECONDS.toNanos(110)) return true;
                    } catch (RuntimeException ignored) { /* Revoked sessions receive no more data. */ }
                    release(key, group, entry.getKey());
                    connection.emitter.complete();
                    return false;
                }).toList();
                if (eligible.isEmpty()) return;
                try (var _ = TenantContext.withTenant(key.tenantId)) {
                    Sample sample = group.latest;
                    if (sample == null || System.nanoTime() - group.sampledAt >= TimeUnit.MILLISECONDS.toNanos(limits.sampleIntervalMs)) {
                        ReviewTask task = task(key.taskId);
                        ReviewTimeline latest = timelines.selectOne(new LambdaQueryWrapper<ReviewTimeline>()
                            .select(ReviewTimeline::getId).eq(ReviewTimeline::getTaskId, key.taskId)
                            .orderByDesc(ReviewTimeline::getId).last("limit 1"));
                        long eventId = latest == null ? 0L : latest.getId();
                        String state = Objects.toString(task.getStatus(), "") + ":"
                            + Objects.toString(task.getLlmStatus(), "") + ":" + Objects.toString(task.getCurrentAttemptId(), "");
                        sample = new Sample(eventId, state, task.getStatus());
                        group.latest = sample;
                        group.sampledAt = System.nanoTime();
                    }
                    for (var entry : eligible) {
                        Connection connection = entry.getValue();
                        if (!group.subscribers.containsKey(entry.getKey())) continue;
                        connection.pending.set(new Delivery(sample, heartbeat));
                        dispatch(key, group, entry.getKey(), connection);
                    }
                } catch (Exception ignored) {
                    eligible.forEach(entry -> {
                        release(key, group, entry.getKey());
                        entry.getValue().emitter.complete();
                    });
                }
            }
        } finally {
            if (heartbeat) group.nextDue = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.sampleIntervalMs);
            if (metrics != null) metrics.timer("repoguard.review.progress.sample.duration")
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void dispatch(TaskKey key, SampleGroup group, String id, Connection connection) {
        if (connection.closed || !connection.sending.compareAndSet(false, true)) return;
        try {
            deliveries.execute(() -> {
                connection.sendingThread = Thread.currentThread();
                long startedAt = System.nanoTime();
                try (var _ = TenantContext.withTenant(key.tenantId)) {
                    Delivery delivery = connection.pending.getAndSet(null);
                    if (connection.closed || delivery == null) return;
                    deliver(key, group, id, connection, delivery);
                } catch (Exception ignored) {
                    if (metrics != null) metrics.counter("repoguard.review.progress.send.failed").increment();
                    release(key, group, id);
                    connection.emitter.complete();
                } finally {
                    connection.sendingThread = null;
                    if (metrics != null) metrics.timer("repoguard.review.progress.send.duration")
                        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                    connection.sending.set(false);
                    if (connection.pending.get() != null) dispatch(key, group, id, connection);
                }
            });
        } catch (RuntimeException rejected) {
            connection.sending.set(false);
            release(key, group, id);
            connection.emitter.complete();
            if (metrics != null) metrics.counter("repoguard.review.progress.send.rejected").increment();
        }
    }

    private void deliver(TaskKey key, SampleGroup group, String id, Connection connection, Delivery delivery) throws Exception {
        authorize(connection.principal, key.tenantId);
        if (connection.closed) return;
        Sample sample = delivery.sample;
        if (connection.previousState == null || sample.eventId != connection.cursor
            || !sample.state.equals(connection.previousState)) {
            connection.emitter.send(SseEmitter.event().name("progress").id(Long.toString(sample.eventId))
                .data(Map.of("taskId", key.taskId)));
            connection.cursor = sample.eventId;
            connection.previousState = sample.state;
        } else if (delivery.heartbeat) connection.emitter.send(SseEmitter.event().comment("keepalive"));
        if (sample.status != null && TERMINAL.contains(sample.status.toUpperCase(java.util.Locale.ROOT))) {
            release(key, group, id);
            connection.emitter.complete();
        }
    }

    @PreDestroy
    public void close() {
        List<Connection> stopped;
        synchronized (this) {
            scheduler.shutdownNow();
            deliveries.shutdownNow();
            stopped = List.copyOf(connections.values());
            stopped.forEach(connection -> { connection.closed = true; connection.pending.set(null); });
            connections.clear();
            groups.clear();
        }
        stopped.forEach(connection -> connection.emitter.complete());
    }

    record TaskKey(long tenantId, Long taskId) { }
    private record Sample(long eventId, String state, String status) { }
    private record Delivery(Sample sample, boolean heartbeat) { }
    static final class SampleGroup {
        final Map<String, Connection> subscribers = new ConcurrentHashMap<>();
        volatile ScheduledFuture<?> future;
        Sample latest;
        long sampledAt;
        volatile long nextDue;
    }

    @ConfigurationProperties(prefix = "repoguard.review.progress-stream")
    public static class Limits {
        private int maxConnections = 16;
        private int perUser = 2;
        private long sampleIntervalMs = 5000;
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int value) {
            if (value < 1 || value > 128) throw new IllegalArgumentException("maxConnections must be 1..128");
            maxConnections = value;
        }
        public int getPerUser() { return perUser; }
        public void setPerUser(int value) {
            if (value < 1 || value > 16) throw new IllegalArgumentException("perUser must be 1..16");
            perUser = value;
        }
        public long getSampleIntervalMs() { return sampleIntervalMs; }
        public void setSampleIntervalMs(long value) {
            if (value < 1000 || value > 30000) throw new IllegalArgumentException("sampleIntervalMs must be 1000..30000");
            sampleIntervalMs = value;
        }
    }

    private static final class Connection {
        private final SseEmitter emitter;
        private final Long taskId;
        private final AuthenticatedPrincipal principal;
        private final long tenantId;
        private final long startedAt = System.nanoTime();
        private volatile ScheduledFuture<?> initialSample;
        private long cursor;
        private String previousState;
        private volatile boolean closed;
        private volatile Thread sendingThread;
        private final AtomicReference<Delivery> pending = new AtomicReference<>();
        private final AtomicBoolean sending = new AtomicBoolean();

        private Connection(SseEmitter emitter, Long taskId, AuthenticatedPrincipal principal, long tenantId, long cursor) {
            this.emitter = emitter;
            this.taskId = taskId;
            this.principal = principal;
            this.tenantId = tenantId;
            this.cursor = cursor;
        }
    }
}
