package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.LlmEvaluationRunExecutorConfig;
import com.repoguard.agent.dto.LlmEvaluationRunDto;
import com.repoguard.agent.dto.LlmEvaluationRunRequest;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.review.LlmEvaluationBudget;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.tenancy.TenantContext;
import jakarta.annotation.PostConstruct;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped asynchronous runner for real-PR evaluation datasets. It enforces budgets before
 * persisting a report and exposes only aggregate progress to callers.
 */
@Service
public class LlmEvaluationRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmEvaluationRunService.class);
    private static final int MAX_RUN_KEY_LENGTH = 128;
    private static final int MAX_OPERATOR_LENGTH = 128;
    private static final long MAX_RUN_TOKENS = 1_000_000L;
    private static final BigDecimal MAX_RUN_COST = new BigDecimal("999999999999.99999999");

    private final LlmEvaluationDatasetLoader datasetLoader;
    private final LlmEvaluationPreviewRunner previewRunner;
    private final LlmModelReleaseService modelReleaseService;
    private final LlmEvaluationRunStore store;
    private final ExecutorService executor;
    private final String runtimeRevision;
    private final ConcurrentMap<RunKey, LlmEvaluationRunState> byKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LlmEvaluationRunState> byId = new ConcurrentHashMap<>();

    public LlmEvaluationRunService(
        LlmEvaluationDatasetLoader datasetLoader,
        LlmEvaluationPreviewRunner previewRunner,
        LlmModelReleaseService modelReleaseService,
        LlmEvaluationRunStore store,
        @Qualifier(LlmEvaluationRunExecutorConfig.EVALUATION_RUN_EXECUTOR) ExecutorService executor
    ) {
        this(datasetLoader, previewRunner, modelReleaseService, store, executor, "unknown");
    }

    @Autowired
    public LlmEvaluationRunService(
        LlmEvaluationDatasetLoader datasetLoader,
        LlmEvaluationPreviewRunner previewRunner,
        LlmModelReleaseService modelReleaseService,
        LlmEvaluationRunStore store,
        @Qualifier(LlmEvaluationRunExecutorConfig.EVALUATION_RUN_EXECUTOR) ExecutorService executor,
        @Value("${REPOGUARD_RUNTIME_REVISION:unknown}") String runtimeRevision
    ) {
        this.datasetLoader = datasetLoader;
        this.previewRunner = previewRunner;
        this.modelReleaseService = modelReleaseService;
        this.store = store;
        this.executor = executor;
        this.runtimeRevision = runtimeRevision != null && runtimeRevision.matches("[0-9a-fA-F]{40}")
            ? runtimeRevision.toLowerCase(java.util.Locale.ROOT) : "unknown";
    }

    @PostConstruct
    void recoverInterruptedRuns() {
        int recovered = store.recoverInterrupted(LocalDateTime.now());
        if (recovered > 0) {
            LOGGER.warn("Recovered {} expired LLM evaluation runs after process restart", recovered);
        }
    }

    public LlmEvaluationRunDto start(LlmEvaluationRunRequest request, String operator) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估运行请求不能为空");
        }
        String runKey = text(request.runKey(), "runKey", MAX_RUN_KEY_LENGTH);
        String directory = text(request.dataDirectory(), "dataDirectory", 512);
        if (request.maxConcurrency() == null || request.maxConcurrency() < 1 || request.maxConcurrency() > 8
            || request.maxTokens() == null || request.maxTokens() < 1 || request.maxTokens() > MAX_RUN_TOKENS
            || request.maxCost() == null || request.maxCost().signum() < 0
            || request.maxCost().compareTo(MAX_RUN_COST) > 0 || request.maxCost().scale() > 8
            || request.maxDurationSeconds() == null || request.maxDurationSeconds() < 1
            || request.maxDurationSeconds() > 3_600) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估运行预算参数无效");
        }
        Path validatedDirectory = datasetLoader.validateDirectory(directory);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        RunKey key = new RunKey(tenantId, runKey);
        LlmEvaluationRunState existing = byKey.get(key);
        if (existing != null) {
            return existing.dto();
        }
        LlmEvaluationRunState state = new LlmEvaluationRunState(
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
        LlmEvaluationRunStore.StoredRun stored = store.createOrGet(state.stored());
        if (!state.runId.equals(stored.runId())) {
            return stored.dto();
        }
        LlmEvaluationRunState raced = byKey.putIfAbsent(key, state);
        if (raced != null) {
            return raced.dto();
        }
        byId.put(state.runId, state);
        try {
            state.future = executor.submit(() -> execute(state));
        } catch (RejectedExecutionException ex) {
            state.fail("RUNNER_UNAVAILABLE");
            persist(state);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "评估运行队列已满");
        }
        return state.dto();
    }

    public LlmEvaluationRunDto get(String runId) {
        String normalizedRunId = normalizedRunId(runId);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LlmEvaluationRunState local = byId.get(normalizedRunId);
        if (local != null && local.tenantId == tenantId) {
            return local.dto();
        }
        LlmEvaluationRunStore.StoredRun stored = store.findByRunId(tenantId, normalizedRunId);
        if (stored == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "评估运行不存在");
        }
        return stored.dto();
    }

    public LlmEvaluationRunDto cancel(String runId) {
        String normalizedRunId = normalizedRunId(runId);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LlmEvaluationRunState state = byId.get(normalizedRunId);
        if (state == null || state.tenantId != tenantId) {
            LlmEvaluationRunStore.StoredRun stored = store.findByRunId(tenantId, normalizedRunId);
            if (stored == null) {
                throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "评估运行不存在");
            }
            if (stored.terminal()) {
                return stored.dto();
            }
            state = LlmEvaluationRunState.from(stored);
        }
        state.requestCancel();
        persist(state);
        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }
        return state.dto();
    }

    private String normalizedRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runId 不能为空");
        }
        return runId.trim();
    }

    LlmEvaluationVersion runtimeVersion(LlmEvaluationVersion declared) {
        return new LlmEvaluationVersion(
            declared.provider(), declared.model(), declared.promptVersion(), declared.contextVersion(),
            declared.schemaVersion(), declared.chunkPolicyVersion(), declared.temperature(),
            declared.ruleVersion(), runtimeRevision, declared.verifierVersion(), declared.aggregationVersion()
        );
    }

    private void execute(LlmEvaluationRunState state) {
        if (!state.markRunning()) {
            return;
        }
        persist(state);
        LlmEvaluationBudget budget = new LlmEvaluationBudget(state.maxTokens, state.maxCost);
        ExecutorService casesExecutor = null;
        try {
            LlmEvaluationDatasetLoader.Dataset dataset = datasetLoader.load(state.dataDirectory);
            state.totalSamples.set(dataset.cases().size());
            persist(state);
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
                    deadline,
                    budget
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
                budget.requireAvailable();
                observations.add(observation);
                state.add(observation);
                persist(state);
                LOGGER.info(
                    "Evaluation sample completed runId={} caseId={} expectedFinding={} predictedFinding={} "
                        + "predictedSeverity={} ruleFindings={} llmFindings={} parseFailed={} "
                        + "transportFailed={} predictionKey={} failureCategories={}",
                    state.runId,
                    observation.caseId(),
                    observation.expectedFinding(),
                    observation.predictedFinding(),
                    observation.predictedSeverity(),
                    observation.ruleFindingCount(),
                    observation.llmFindingCount(),
                    observation.parseFailed(),
                    observation.transportFailed(),
                    observation.predictionKey(),
                    observation.failureCategories()
                );
                if (state.totalTokens.get() > state.maxTokens
                    || state.totalCost.get().compareTo(state.maxCost) > 0) {
                    throw new LlmEvaluationBudget.BudgetExceededException();
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
                    runtimeVersion(dataset.version()),
                    dataset.metadata(),
                    observations,
                    dataset.minimumSamples(),
                    state.operator
                );
                state.complete(report.id());
                persist(state);
            }
        } catch (CancellationException ex) {
            state.cancelled.set(true);
            state.cancel();
            state.accountBudget(budget);
            persist(state);
        } catch (TimeoutException | LlmEvaluationBudget.BudgetExceededException ex) {
            state.accountBudget(budget);
            state.fail("BUDGET_EXHAUSTED");
            persist(state);
        } catch (ExecutionException ex) {
            state.accountBudget(budget);
            state.fail(classifyFailure(ex.getCause()));
            persist(state);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            state.cancelled.set(true);
            state.cancel();
            state.accountBudget(budget);
            persist(state);
        } catch (RuntimeException ex) {
            state.accountBudget(budget);
            state.fail(classifyFailure(ex));
            persist(state);
        } finally {
            if (casesExecutor != null) {
                casesExecutor.shutdownNow();
            }
        }
    }

    private String classifyFailure(Throwable failure) {
        if (failure instanceof LlmEvaluationBudget.BudgetExceededException || failure instanceof TimeoutException) {
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

    private void persist(LlmEvaluationRunState state) {
        store.save(state.stored());
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

}
