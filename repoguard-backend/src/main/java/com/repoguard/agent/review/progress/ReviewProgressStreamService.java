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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded, coalesced invalidations sourced from durable task/timeline state, never worker transactions. */
@Service
@ApiRuntimeEnabled
@ConditionalOnProperty(name = "repoguard.review.progress-stream-enabled", havingValue = "true")
public class ReviewProgressStreamService {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "SUPERSEDED",
        "PENDING_HUMAN_REVIEW", "APPROVED", "CHANGES_REQUESTED", "REJECTED");
    private final ReviewTaskMapper tasks;
    private final ReviewTimelineMapper timelines;
    private final AuthAccountCache accounts;
    private final TenantProperties tenantProperties;
    private final TenantResolutionService tenantResolution;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "review-progress-stream");
        thread.setDaemon(true);
        return thread;
    });

    public ReviewProgressStreamService(ReviewTaskMapper tasks, ReviewTimelineMapper timelines,
                                      AuthAccountCache accounts, TenantProperties tenantProperties,
                                      TenantResolutionService tenantResolution) {
        this.tasks = tasks;
        this.timelines = timelines;
        this.accounts = accounts;
        this.tenantProperties = tenantProperties;
        this.tenantResolution = tenantResolution;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public synchronized SseEmitter open(Long taskId, AuthenticatedPrincipal principal, Long lastEventId) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        authorize(principal, tenantId);
        task(taskId); // Synchronous 404 and tenant check before HTTP headers are committed.
        long perUser = connections.values().stream().filter(c -> c.principal.id().equals(principal.id())).count();
        if (connections.size() >= 16 || perUser >= 2) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Progress stream limit reached");
        }
        String key = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(120_000L);
        Connection connection = new Connection(emitter, taskId, principal, tenantId,
            lastEventId == null ? 0L : lastEventId);
        connections.put(key, connection);
        Runnable release = () -> connections.remove(key);
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(ignored -> release.run());
        var future = scheduler.scheduleWithFixedDelay(() -> tick(key, connection), 0, 5, TimeUnit.SECONDS);
        emitter.onCompletion(() -> future.cancel(false));
        emitter.onTimeout(() -> future.cancel(false));
        emitter.onError(ignored -> future.cancel(false));
        if (!connections.containsKey(key)) {
            future.cancel(false);
        }
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

    private void tick(String key, Connection connection) {
        if (!connections.containsKey(key)) {
            return;
        }
        try (var _ = TenantContext.withTenant(connection.tenantId)) {
            authorize(connection.principal, connection.tenantId);
            if (System.nanoTime() - connection.startedAt >= TimeUnit.SECONDS.toNanos(110)) {
                connection.emitter.complete();
                return;
            }
            ReviewTask task = task(connection.taskId);
            ReviewTimeline latest = timelines.selectOne(new LambdaQueryWrapper<ReviewTimeline>()
                .select(ReviewTimeline::getId).eq(ReviewTimeline::getTaskId, connection.taskId)
                .orderByDesc(ReviewTimeline::getId).last("limit 1"));
            long eventId = latest == null ? 0L : latest.getId();
            String state = Objects.toString(task.getStatus(), "") + ":"
                + Objects.toString(task.getLlmStatus(), "") + ":" + Objects.toString(task.getCurrentAttemptId(), "");
            // Always resync on connect, including after restart, cursor expiry, or a forged future cursor.
            // Events coalesce intervening changes: the authenticated status endpoint is authoritative.
            if (connection.previousState == null || eventId != connection.cursor || !state.equals(connection.previousState)) {
                connection.emitter.send(SseEmitter.event().name("progress").id(Long.toString(eventId))
                    .data(Map.of("taskId", connection.taskId)));
                connection.cursor = eventId;
                connection.previousState = state;
            } else {
                connection.emitter.send(SseEmitter.event().comment("keepalive"));
            }
            if (task.getStatus() != null && TERMINAL.contains(task.getStatus().toUpperCase(java.util.Locale.ROOT))) {
                connection.emitter.complete();
            }
        } catch (Exception failure) {
            connections.remove(key);
            connection.emitter.complete(); // No exception text or provider payload on the wire.
        }
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        connections.values().forEach(connection -> connection.emitter.complete());
        connections.clear();
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
