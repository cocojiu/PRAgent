package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.LlmEvaluationRunExecutorConfig;
import com.repoguard.agent.dto.LlmEvaluationRunDto;
import com.repoguard.agent.dto.LlmEvaluationRunRequest;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped asynchronous runner for real-PR evaluation datasets. It enforces budgets before
 * persisting a report and exposes only aggregate progress to callers.
 */
@Service
public class LlmEvaluationRunService {

    private static final int MAX_RUN_KEY_LENGTH = 128;
    private static final int MAX_OPERATOR_LENGTH = 128;

    private final LlmEvaluationDatasetLoader datasetLoader;
    private final LlmEvaluationPreviewRunner previewRunner;
    private final LlmModelReleaseService modelReleaseService;
    private final ExecutorService executor;
    private final ConcurrentMap<RunKey, RunState> byKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RunState> byId = new ConcurrentHashMap<>();

    public LlmEvaluationRunService(
        LlmEvaluationDatasetLoader datasetLoader,
        LlmEvaluationPreviewRunner previewRunner,
        LlmModelReleaseService modelReleaseService,
        @Qualifier(LlmEvaluationRunExecutorConfig.EVALUATION_RUN_EXECUTOR) ExecutorService executor
    ) {
        this.datasetLoader = datasetLoader;
        this.previewRunner = previewRunner;
        this.modelReleaseService = modelReleaseService;
        this.executor = executor;
    }

    public LlmEvaluationRunDto start(LlmEvaluationRunRequest request, String operator) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估运行请求不能为空");
        }
        String runKey = text(request.runKey(), "runKey", MAX_RUN_KEY_LENGTH);
        String directory = text(request.dataDirectory(), "dataDirectory", 512);
        if (request.maxConcurrency() == null || request.maxConcurrency() < 1 || request.maxConcurrency() > 8
            || request.maxTokens() == null || request.maxTokens() < 1
            || request.maxCost() == null || request.maxCost().signum() < 0
            || request.maxDurationSeconds() == null || request.maxDurationSeconds() < 1
            || request.maxDurationSeconds() > 3_600) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估运行预算参数无效");
        }
        Path validatedDirectory = datasetLoader.validateDirectory(directory);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        RunKey key = new RunKey(tenantId, runKey);
        RunState existing = byKey.get(key);
        if (existing != null) {
            return existing.dto();
        }
        RunState state = new RunState(
            tenantId,
            UUID.randomUUID().toString(),
            runKey,
            validatedDirectory.toString(),
            request.maxConcurrency(),
            request.maxTokens(),
            request.maxCost(),
            request.maxDurationSeconds(),
            normalizeOperator(operator)
        );
        RunState raced = byKey.putIfAbsent(key, state);
        if (raced != null) {
            return raced.dto();
        }
        byId.put(state.runId, state);
        try {
            state.future = executor.submit(() -> execute(state));
        } catch (RejectedExecutionException ex) {
            state.fail("RUNNER_UNAVAILABLE");
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "评估运行队列已满");
        }
        return state.dto();
    }

    public LlmEvaluationRunDto get(String runId) {
        return stateForTenant(runId).dto();
    }

    public LlmEvaluationRunDto cancel(String runId) {
        RunState state = stateForTenant(runId);
        state.requestCancel();
        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }
        return state.dto();
    }

    private RunState stateForTenant(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runId 不能为空");
        }
        RunState state = byId.get(runId.trim());
        if (state == null || state.tenantId != TenantContext.currentTenantIdOrDefault()) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "评估运行不存在");
        }
        return state;
    }

    private void execute(RunState state) {
        if (!state.markRunning()) {
            return;
        }
        ExecutorService casesExecutor = null;
        try {
            LlmEvaluationDatasetLoader.Dataset dataset = datasetLoader.load(state.dataDirectory);
            state.totalSamples.set(dataset.cases().size());
            ReviewDeadline deadline = ReviewDeadline.startingNow(
                Duration.ofSeconds(state.maxDurationSeconds)
            );
            casesExecutor = Executors.newFixedThreadPool(
                Math.max(1, state.maxConcurrency),
                runnable -> {
                    Thread thread = new Thread(runnable, "repoguard-evaluation-case-" + state.runId);
                    thread.setDaemon(true);
                    return thread;
                }
            );
            List<Future<LlmEvaluationObservation>> futures = new ArrayList<>();
            for (LlmEvaluationDatasetLoader.EvaluationCase sample : dataset.cases()) {
                if (state.cancelled.get()) {
                    throw new CancellationException();
                }
                futures.add(casesExecutor.submit(() -> previewRunner.run(
                    sample,
                    dataset.version().provider(),
                    dataset.version().model(),
                    deadline
                )));
            }

            List<LlmEvaluationObservation> observations = new ArrayList<>(futures.size());
            for (Future<LlmEvaluationObservation> future : futures) {
                if (state.cancelled.get()) {
                    throw new CancellationException();
                }
                long remaining = deadline.remainingNanos();
                if (remaining <= 0) {
                    throw new TimeoutException("evaluation deadline exhausted");
                }
                LlmEvaluationObservation observation = future.get(remaining, TimeUnit.NANOSECONDS);
                observations.add(observation);
                state.add(observation);
                org.slf4j.LoggerFactory.getLogger(LlmEvaluationRunService.class).info("Evaluation sample completed runId={} caseId={} expectedFinding={} predictedFinding={} predictedSeverity={} ruleFindings={} llmFindings={} parseFailed={} transportFailed={} predictionKey={}", state.runId, observation.caseId(), observation.expectedFinding(), observation.predictedFinding(), observation.predictedSeverity(), observation.ruleFindingCount(), observation.llmFindingCount(), observation.parseFailed(), observation.transportFailed(), observation.predictionKey());
                if (state.totalTokens.get() > state.maxTokens
                    || state.totalCost.get().compareTo(state.maxCost) > 0) {
                    throw new BudgetExceededException();
                }
            }
            if (state.cancelled.get()) {
                throw new CancellationException();
            }
            synchronized (state) {
                if (state.cancelled.get()) {
                    throw new CancellationException();
                }
                LlmModelReleaseDto.EvaluationReportDto report = modelReleaseService.createEvaluationReport(
                    dataset.version(),
                    dataset.metadata(),
                    observations,
                    dataset.minimumSamples(),
                    state.operator
                );
                state.complete(report.id());
            }
        } catch (CancellationException ex) {
            state.cancelled.set(true);
            state.cancel();
        } catch (TimeoutException | BudgetExceededException ex) {
            state.fail("BUDGET_EXHAUSTED");
        } catch (ExecutionException ex) {
            state.fail(classifyFailure(ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            state.cancelled.set(true);
            state.cancel();
        } catch (RuntimeException ex) {
            state.fail(classifyFailure(ex));
        } finally {
            if (casesExecutor != null) {
                casesExecutor.shutdownNow();
            }
        }
    }

    private String classifyFailure(Throwable failure) {
        if (failure instanceof BudgetExceededException || failure instanceof TimeoutException) {
            return "BUDGET_EXHAUSTED";
        }
        if (failure instanceof BusinessException) {
            return "DATASET_INVALID";
        }
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return "RUNNER_UNAVAILABLE";
        }
        return "INTERNAL_FAILURE";
    }

    private String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 参数无效");
        }
        return value.trim();
    }

    private String normalizeOperator(String value) {
        if (value == null || value.isBlank()) {
            return "system";
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_OPERATOR_LENGTH
            ? normalized
            : normalized.substring(0, MAX_OPERATOR_LENGTH);
    }

    private record RunKey(long tenantId, String runKey) { }

    private static final class BudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class RunState {
        private final long tenantId;
        private final String runId;
        private final String runKey;
        private final String dataDirectory;
        private final int maxConcurrency;
        private final long maxTokens;
        private final BigDecimal maxCost;
        private final int maxDurationSeconds;
        private final String operator;
        private final AtomicReference<String> status = new AtomicReference<>("QUEUED");
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger totalSamples = new AtomicInteger();
        private final AtomicInteger completedSamples = new AtomicInteger();
        private final AtomicLong totalTokens = new AtomicLong();
        private final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);
        private final AtomicReference<Long> reportId = new AtomicReference<>();
        private final AtomicReference<String> failureCode = new AtomicReference<>();
        private final LocalDateTime submittedAt = LocalDateTime.now();
        private volatile LocalDateTime startedAt;
        private volatile LocalDateTime finishedAt;
        private volatile Future<?> future;

        private RunState(
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
            this.tenantId = tenantId;
            this.runId = runId;
            this.runKey = runKey;
            this.dataDirectory = dataDirectory;
            this.maxConcurrency = maxConcurrency;
            this.maxTokens = maxTokens;
            this.maxCost = maxCost;
            this.maxDurationSeconds = maxDurationSeconds;
            this.operator = operator;
        }

        private boolean markRunning() {
            boolean running = status.compareAndSet("QUEUED", "RUNNING");
            if (running) {
                startedAt = LocalDateTime.now();
            }
            return running;
        }

        private void add(LlmEvaluationObservation observation) {
            completedSamples.incrementAndGet();
            totalTokens.addAndGet(Math.max(0L, observation.totalTokens()));
            totalCost.accumulateAndGet(
                observation.estimatedCost() == null ? BigDecimal.ZERO : observation.estimatedCost().max(BigDecimal.ZERO),
                BigDecimal::add
            );
        }

        private synchronized void requestCancel() {
            cancelled.set(true);
            cancel();
        }

        private synchronized void cancel() {
            String current = status.get();
            if (!"COMPLETE".equals(current) && !"FAILED".equals(current)) {
                status.set("CANCELLED");
                finishedAt = LocalDateTime.now();
                failureCode.set("CANCELLED");
            }
        }

        private synchronized void complete(Long id) {
            if (cancelled.get()) {
                cancel();
                return;
            }
            if (status.compareAndSet("RUNNING", "COMPLETE")) {
                reportId.set(id);
                finishedAt = LocalDateTime.now();
            }
        }

        private synchronized void fail(String code) {
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

        private LlmEvaluationRunDto dto() {
            int total = totalSamples.get();
            return new LlmEvaluationRunDto(
                runId,
                runKey,
                status.get(),
                Math.max(0, total),
                completedSamples.get(),
                totalTokens.get(),
                totalCost.get(),
                reportId.get(),
                failureCode.get(),
                submittedAt,
                startedAt,
                finishedAt
            );
        }
    }
}
