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
        this.limits = limits;
        this.tasks = tasks;
        this.timelines = timelines;
        this.accounts = accounts;
        this.tenantProperties = tenantProperties;
        this.tenantResolution = tenantResolution;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public synchronized SseEmitter open(Long taskId, AuthenticatedPrincipal principal, Long lastEventId) {
        if (scheduler.isShutdown()) throw new IllegalStateException("Progress stream is closed");
        long tenantId = tenantProperties.currentTenantId();
        authorize(principal, tenantId);
        task(taskId); // Synchronous 404 and tenant check before HTTP headers are committed.
        long perUser = connections.values().stream().filter(c -> c.principal.id().equals(principal.id())).count();
        if (connections.size() >= limits.maxConnections || perUser >= limits.perUser) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Progress stream limit reached");
        }
        String key = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(120_000L);
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
            group.future = scheduler.scheduleWithFixedDelay(() -> tick(taskKey, group),
                limits.sampleIntervalMs, limits.sampleIntervalMs, TimeUnit.MILLISECONDS);
        }
        scheduler.execute(() -> tick(taskKey, group, false));
        return emitter;
    }

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
        connections.remove(key);
        group.subscribers.remove(key);
        if (group.subscribers.isEmpty() && groups.remove(taskKey, group) && group.future != null) {
            group.future.cancel(false);
        }
    }

    void tick(TaskKey key, SampleGroup group) { tick(key, group, true); }

    private void tick(TaskKey key, SampleGroup group, boolean heartbeat) {
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
                    try {
                        // Resync new subscribers immediately, including restart, stale and future cursors.
                        if (connection.previousState == null || sample.eventId != connection.cursor || !sample.state.equals(connection.previousState)) {
                            connection.emitter.send(SseEmitter.event().name("progress").id(Long.toString(sample.eventId))
                                .data(Map.of("taskId", key.taskId)));
                            connection.cursor = sample.eventId;
                            connection.previousState = sample.state;
                        } else if (heartbeat) connection.emitter.send(SseEmitter.event().comment("keepalive"));
                        if (sample.status != null && TERMINAL.contains(sample.status.toUpperCase(java.util.Locale.ROOT))) {
                            release(key, group, entry.getKey());
                            connection.emitter.complete();
                        }
                    } catch (Exception ignored) {
                        release(key, group, entry.getKey());
                        connection.emitter.complete();
                    }
                }
            } catch (Exception ignored) {
                eligible.forEach(entry -> {
                    release(key, group, entry.getKey());
                    entry.getValue().emitter.complete();
                });
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        scheduler.shutdownNow();
        connections.values().forEach(connection -> connection.emitter.complete());
        connections.clear();
        groups.clear();
    }

    record TaskKey(long tenantId, Long taskId) { }
    private record Sample(long eventId, String state, String status) { }
    static final class SampleGroup {
        final Map<String, Connection> subscribers = new ConcurrentHashMap<>();
        volatile ScheduledFuture<?> future;
        Sample latest;
        long sampledAt;
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
        private long cursor;
        private String previousState;

        private Connection(SseEmitter emitter, Long taskId, AuthenticatedPrincipal principal, long tenantId, long cursor) {
            this.emitter = emitter;
            this.taskId = taskId;
            this.principal = principal;
            this.tenantId = tenantId;
            this.cursor = cursor;
        }
    }
}
