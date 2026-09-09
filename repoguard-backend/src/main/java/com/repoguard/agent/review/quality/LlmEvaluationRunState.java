package com.repoguard.agent.review.quality;

import com.repoguard.agent.dto.LlmEvaluationRunDto;
import com.repoguard.agent.review.LlmEvaluationBudget;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe in-process state for one durable evaluation run. */
final class LlmEvaluationRunState {
    final long tenantId;
    final String runId;
    final String runKey;
    final String dataDirectory;
    final int maxConcurrency;
    final long maxTokens;
    final BigDecimal maxCost;
    final int maxDurationSeconds;
    final String operator;
    final AtomicBoolean cancelled = new AtomicBoolean();
    final AtomicInteger totalSamples = new AtomicInteger();
    final AtomicLong totalTokens = new AtomicLong();
    final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);
    volatile Future<?> future;

    private final AtomicReference<String> status = new AtomicReference<>("QUEUED");
    private final AtomicInteger completedSamples = new AtomicInteger();
    private final AtomicReference<Long> reportId = new AtomicReference<>();
    private final AtomicReference<String> failureCode = new AtomicReference<>();
    private final LocalDateTime submittedAt;
    private volatile LocalDateTime startedAt;
    private volatile LocalDateTime finishedAt;

    LlmEvaluationRunState(
        long tenantId,
        String runId,
        String runKey,
        String dataDirectory,
        int maxConcurrency,
        long maxTokens,
        BigDecimal maxCost,
        int maxDurationSeconds,
        String operator
    ) {
        this(
            tenantId,
            runId,
            runKey,
            dataDirectory,
            maxConcurrency,
            maxTokens,
            maxCost,
            maxDurationSeconds,
            operator,
            LocalDateTime.now()
        );
    }

    private LlmEvaluationRunState(
        long tenantId,
        String runId,
        String runKey,
        String dataDirectory,
        int maxConcurrency,
        long maxTokens,
        BigDecimal maxCost,
        int maxDurationSeconds,
        String operator,
        LocalDateTime submittedAt
    ) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.runKey = runKey;
        this.dataDirectory = dataDirectory;
        this.maxConcurrency = maxConcurrency;
        this.maxTokens = maxTokens;
        this.maxCost = maxCost;
        this.maxDurationSeconds = maxDurationSeconds;
        this.operator = operator;
        this.submittedAt = submittedAt;
    }

    static LlmEvaluationRunState from(LlmEvaluationRunStore.StoredRun stored) {
        LlmEvaluationRunState state = new LlmEvaluationRunState(
            stored.tenantId(), stored.runId(), stored.runKey(), stored.dataDirectory(),
            stored.maxConcurrency(), stored.maxTokens(), stored.maxCost(), stored.maxDurationSeconds(),
            stored.operator(), stored.submittedAt()
        );
        state.status.set(stored.status());
        state.totalSamples.set(stored.totalSamples());
        state.completedSamples.set(stored.completedSamples());
        state.totalTokens.set(stored.totalTokens());
        state.totalCost.set(stored.totalCost());
        state.reportId.set(stored.reportId());
        state.failureCode.set(stored.failureCode());
        state.startedAt = stored.startedAt();
        state.finishedAt = stored.finishedAt();
        state.cancelled.set("CANCELLED".equals(stored.status()));
        return state;
    }

    boolean markRunning() {
        boolean running = status.compareAndSet("QUEUED", "RUNNING");
        if (running) {
            startedAt = LocalDateTime.now();
        }
        return running;
    }

    void add(LlmEvaluationObservation observation) {
        completedSamples.incrementAndGet();
        totalTokens.addAndGet(Math.max(0L, observation.totalTokens()));
        totalCost.accumulateAndGet(observation.estimatedCost().max(BigDecimal.ZERO), BigDecimal::add);
    }

    void accountBudget(LlmEvaluationBudget budget) {
        totalTokens.accumulateAndGet(budget.accountedTokens(), Math::max);
        totalCost.accumulateAndGet(budget.accountedCost(), BigDecimal::max);
    }

    synchronized void requestCancel() {
        cancelled.set(true);
        cancel();
    }

    synchronized void cancel() {
        String current = status.get();
        if (!"COMPLETE".equals(current) && !"FAILED".equals(current)) {
            status.set("CANCELLED");
            finishedAt = LocalDateTime.now();
            failureCode.set("CANCELLED");
        }
    }

    synchronized void complete(Long id) {
        if (cancelled.get()) {
            cancel();
        } else if (status.compareAndSet("RUNNING", "COMPLETE")) {
            reportId.set(id);
            finishedAt = LocalDateTime.now();
        }
    }

    synchronized void fail(String code) {
        if (cancelled.get()) {
            cancel();
            return;
        }
        String current = status.get();
        if (!"COMPLETE".equals(current) && !"CANCELLED".equals(current)) {
            status.set("FAILED");
            failureCode.set(code);
            finishedAt = LocalDateTime.now();
        }
    }

    LlmEvaluationRunDto dto() {
        return new LlmEvaluationRunDto(
            runId, runKey, status.get(), Math.max(0, totalSamples.get()), completedSamples.get(),
            totalTokens.get(), totalCost.get(), reportId.get(), failureCode.get(), submittedAt,
            startedAt, finishedAt
        );
    }

    LlmEvaluationRunStore.StoredRun stored() {
        return new LlmEvaluationRunStore.StoredRun(
            tenantId, runId, runKey, status.get(), dataDirectory, maxConcurrency, maxTokens,
            maxCost, maxDurationSeconds, operator, totalSamples.get(), completedSamples.get(),
            totalTokens.get(), totalCost.get(), reportId.get(), failureCode.get(), submittedAt,
            startedAt, finishedAt
        );
    }
}
