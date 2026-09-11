package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmEvaluationRunDto;
import com.repoguard.agent.dto.LlmEvaluationRunRequest;
import com.repoguard.agent.review.LlmEvaluationBudget;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LlmEvaluationRunServiceTest {

    private final LlmEvaluationDatasetLoader datasetLoader = org.mockito.Mockito.mock(LlmEvaluationDatasetLoader.class);
    private final LlmEvaluationPreviewRunner previewRunner = org.mockito.Mockito.mock(LlmEvaluationPreviewRunner.class);
    private final LlmModelReleaseService modelReleaseService = org.mockito.Mockito.mock(LlmModelReleaseService.class);
    private final FakeRunStore store = new FakeRunStore();
    private TenantContext.Scope tenantScope;

    @AfterEach
    void clearTenant() {
        if (tenantScope != null) {
            tenantScope.close();
        }
    }

    @Test
    void executesDatasetAndPersistsOnlyTheAggregateReport() throws Exception {
        tenantScope = TenantContext.withTenant(42L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmEvaluationDatasetLoader.EvaluationCase sample = sample();
            LlmEvaluationDatasetLoader.Dataset dataset = dataset(sample);
            when(datasetLoader.validateDirectory("dataset")).thenReturn(Path.of("C:/evaluation/dataset"));
            when(datasetLoader.load(anyString())).thenReturn(dataset);
            LlmEvaluationObservation observation = observation(3, new BigDecimal("0.02"));
            when(previewRunner.run(
                any(), anyString(), anyString(), any(ReviewDeadline.class), any(LlmEvaluationBudget.class)
            )).thenReturn(observation);
            LlmModelReleaseDto.EvaluationReportDto report = org.mockito.Mockito.mock(LlmModelReleaseDto.EvaluationReportDto.class);
            when(report.id()).thenReturn(77L);
            when(modelReleaseService.createEvaluationReport(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(report);

            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, store, executor,
                "15afe07737b5f90515cef13b8b37e6ac332c182f"
            );
            LlmEvaluationRunDto queued = service.start(request("run-1", 100, "1.00"), "operator");
            LlmEvaluationRunDto same = service.start(request("run-1", 100, "1.00"), "operator");
            LlmEvaluationRunDto completed = await(service, queued.runId(), "COMPLETE");

            assertThat(same.runId()).isEqualTo(queued.runId());
            assertThat(completed.completedSamples()).isEqualTo(1);
            assertThat(completed.totalSamples()).isEqualTo(1);
            assertThat(completed.totalTokens()).isEqualTo(3);
            assertThat(completed.totalCost()).isEqualByComparingTo("0.02");
            assertThat(completed.reportId()).isEqualTo(77L);
            ExecutorService restartedExecutor = Executors.newSingleThreadExecutor();
            try {
                LlmEvaluationRunService restarted = new LlmEvaluationRunService(
                    datasetLoader, previewRunner, modelReleaseService, store, restartedExecutor
                );
                assertThat(restarted.get(queued.runId())).isEqualTo(completed);
            } finally {
                restartedExecutor.shutdownNow();
            }
            verify(modelReleaseService).createEvaluationReport(
                eq(service.runtimeVersion(dataset.version())), eq(dataset.metadata()), any(), anyInt(), anyString()
            );
            assertThat(service.runtimeVersion(dataset.version()).codeRevision())
                .isEqualTo("15afe07737b5f90515cef13b8b37e6ac332c182f");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingRuntimeRevisionDoesNotBorrowTheDatasetRevision() {
        LlmEvaluationRunService service = new LlmEvaluationRunService(
            datasetLoader, previewRunner, modelReleaseService, store, null
        );
        LlmEvaluationVersion version = service.runtimeVersion(dataset(sample()).version());
        assertThat(version.codeRevision()).isEqualTo("unknown");
        assertThat(version.reproducible()).isFalse();
    }

    @Test
    void invalidRuntimeRevisionCannotBeUsedAsReleaseEvidence() {
        LlmEvaluationRunService service = new LlmEvaluationRunService(
            datasetLoader, previewRunner, modelReleaseService, store, null, "main-latest"
        );
        assertThat(service.runtimeVersion(dataset(sample()).version()).codeRevision()).isEqualTo("unknown");
    }

    @Test
    void swallowedBudgetFailureCannotProduceACompletedReport() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            when(datasetLoader.validateDirectory("dataset")).thenReturn(Path.of("C:/evaluation/dataset"));
            when(datasetLoader.load(anyString())).thenReturn(dataset(sample()));
            when(previewRunner.run(any(), anyString(), anyString(), any(ReviewDeadline.class),
                any(LlmEvaluationBudget.class))).thenAnswer(invocation -> {
                    LlmEvaluationBudget budget = invocation.getArgument(4);
                    try {
                        budget.reserve("system", "user", 2000, BigDecimal.ZERO, BigDecimal.ZERO);
                    } catch (LlmEvaluationBudget.BudgetExceededException ignored) {
                        // Reproduce an external-call wrapper returning a rule fallback.
                    }
                    return observation(0, BigDecimal.ZERO);
                });
            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, store, executor
            );
            LlmEvaluationRunDto run = service.start(request("swallowed-budget", 100, "1.00"), "operator");
            LlmEvaluationRunDto failed = await(service, run.runId(), "FAILED");
            assertThat(failed.failureCode()).isEqualTo("BUDGET_EXHAUSTED");
            assertThat(failed.reportId()).isNull();
            assertThat(failed.completedSamples()).isZero();
            verify(modelReleaseService, never()).createEvaluationReport(any(), any(), any(), anyInt(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startupRecoveryMarksPersistedInFlightRunsAsInterrupted() {
        tenantScope = TenantContext.withTenant(42L);
        LocalDateTime submittedAt = LocalDateTime.now().minusMinutes(2);
        store.createOrGet(new LlmEvaluationRunStore.StoredRun(
            42L,
            "persisted-run",
            "persisted-key",
            "RUNNING",
            "C:/evaluation/dataset",
            1,
            100L,
            BigDecimal.ONE,
            60,
            "operator",
            20,
            3,
            30L,
            new BigDecimal("0.01"),
            null,
            null,
            submittedAt,
            submittedAt.plusSeconds(1),
            null
        ));
        store.createOrGet(new LlmEvaluationRunStore.StoredRun(
            42L,
            "active-run",
            "active-key",
            "RUNNING",
            "C:/evaluation/dataset",
            1,
            100L,
            BigDecimal.ONE,
            60,
            "operator",
            20,
            3,
            30L,
            new BigDecimal("0.01"),
            null,
            null,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null
        ));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, store, executor
            );
            service.recoverInterruptedRuns();

            LlmEvaluationRunDto recovered = service.get("persisted-run");
            assertThat(recovered.status()).isEqualTo("FAILED");
            assertThat(recovered.failureCode()).isEqualTo("RUN_INTERRUPTED");
            assertThat(recovered.completedSamples()).isEqualTo(3);
            assertThat(recovered.finishedAt()).isNotNull();
            assertThat(service.get("active-run").status()).isEqualTo("RUNNING");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordsDatasetFailureAndBudgetExhaustion() throws Exception {
        tenantScope = TenantContext.withTenant(42L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            when(datasetLoader.validateDirectory(anyString())).thenReturn(Path.of("C:/evaluation/dataset"));
            when(datasetLoader.load(anyString()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "invalid manifest"))
                .thenReturn(dataset(sample()));
            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, store, executor
            );
            LlmEvaluationRunDto invalid = service.start(request("invalid", 100, "1.00"), "operator");
            assertThat(await(service, invalid.runId(), "FAILED").failureCode()).isEqualTo("DATASET_INVALID");

            when(previewRunner.run(
                any(), anyString(), anyString(), any(ReviewDeadline.class), any(LlmEvaluationBudget.class)
            ))
                .thenReturn(observation(10, new BigDecimal("0.02")));
            LlmEvaluationRunDto budget = service.start(request("budget", 1, "1.00"), "operator");
            assertThat(await(service, budget.runId(), "FAILED").failureCode()).isEqualTo("BUDGET_EXHAUSTED");
            verify(modelReleaseService, never()).createEvaluationReport(any(), any(), any(), anyInt(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationStopsAnInFlightRunAndUnknownTenantCannotReadIt() throws Exception {
        tenantScope = TenantContext.withTenant(42L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            when(datasetLoader.validateDirectory(anyString())).thenReturn(Path.of("C:/evaluation/dataset"));
            when(datasetLoader.load(anyString())).thenReturn(dataset(sample()));
            when(previewRunner.run(
                any(), anyString(), anyString(), any(ReviewDeadline.class), any(LlmEvaluationBudget.class)
            )).thenAnswer(invocation -> {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
                return observation(1, BigDecimal.ZERO);
            });
            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, store, executor
            );
            LlmEvaluationRunDto started = service.start(request("cancel", 100, "1.00"), "operator");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(started.runId()).status()).isEqualTo("CANCELLED");
            release.countDown();
            assertThat(await(service, started.runId(), "CANCELLED").failureCode()).isEqualTo("CANCELLED");

            TenantContext.Scope otherTenant = TenantContext.withTenant(99L);
            try {
                assertThatThrownBy(() -> service.get(started.runId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不存在");
            } finally {
                otherTenant.close();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidRequestsAndUnavailableQueue() {
        tenantScope = TenantContext.withTenant(42L);
        ExecutorService executor = org.mockito.Mockito.mock(ExecutorService.class);
        LlmEvaluationRunService service = new LlmEvaluationRunService(
            datasetLoader, previewRunner, modelReleaseService, store, executor
        );
        assertThatThrownBy(() -> service.start(null, "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请求不能为空");
        assertThatThrownBy(() -> service.start(request("", 100, "1.00"), "operator"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get("missing"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
        when(datasetLoader.validateDirectory(anyString())).thenReturn(Path.of("C:/evaluation/dataset"));
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));
        assertThatThrownBy(() -> service.start(request("queue", 100, "1.00"), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("队列已满");
    }

    private LlmEvaluationRunDto await(LlmEvaluationRunService service, String runId, String status)
        throws InterruptedException {
        LlmEvaluationRunDto latest = service.get(runId);
        for (int index = 0; index < 200 && !status.equals(latest.status()); index++) {
            Thread.sleep(10);
            latest = service.get(runId);
        }
        assertThat(latest.status()).isEqualTo(status);
        return latest;
    }

    private LlmEvaluationRunRequest request(String runKey, long maxTokens, String maxCost) {
        return new LlmEvaluationRunRequest(runKey, "dataset", 1, maxTokens, new BigDecimal(maxCost), 5);
    }

    private LlmEvaluationDatasetLoader.Dataset dataset(LlmEvaluationDatasetLoader.EvaluationCase sample) {
        LlmEvaluationDatasetMetadata metadata = new LlmEvaluationDatasetMetadata(
            "dataset", "v1", LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR, 2, 1, 1, 0,
            true, true, true, "a".repeat(64)
        );
        LlmEvaluationVersion version = new LlmEvaluationVersion(
            "openai", "gpt-test", "prompt", "context", "schema", "chunk", BigDecimal.ZERO, "rules", "commit"
        );
        return new LlmEvaluationDatasetLoader.Dataset(metadata, version, List.of(sample), 1);
    }

    private LlmEvaluationDatasetLoader.EvaluationCase sample() {
        return new LlmEvaluationDatasetLoader.EvaluationCase(
            "case-1", "repo-1", "FIXED_REGRESSION", "java", "jvm", null, false, "NONE", "org", "repo", 1,
            "head", "title", "main", List.of(new LlmEvaluationDatasetLoader.EvaluationFile(
                "src/App.java", "modified", 1, 1, "patch"
            )), Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE
        );
    }

    private LlmEvaluationObservation observation(long tokens, BigDecimal cost) {
        return new LlmEvaluationObservation(
            "case-1", "jvm", false, "NONE", false, "NONE", true, "", true,
            10, tokens, cost
        );
    }

    private static final class FakeRunStore implements LlmEvaluationRunStore {
        private final Map<String, StoredRun> runs = new ConcurrentHashMap<>();

        @Override
        public StoredRun createOrGet(StoredRun candidate) {
            return runs.computeIfAbsent(key(candidate.tenantId(), candidate.runKey()), ignored -> candidate);
        }

        @Override
        public StoredRun findByRunId(long tenantId, String runId) {
            return runs.values().stream()
                .filter(run -> run.tenantId() == tenantId && run.runId().equals(runId))
                .findFirst()
                .orElse(null);
        }

        @Override
        public void save(StoredRun run) {
            runs.put(key(run.tenantId(), run.runKey()), run);
        }

        @Override
        public int recoverInterrupted(LocalDateTime recoveredAt) {
            int recovered = 0;
            for (Map.Entry<String, StoredRun> entry : runs.entrySet()) {
                StoredRun run = entry.getValue();
                if (!"QUEUED".equals(run.status()) && !"RUNNING".equals(run.status())) {
                    continue;
                }
                LocalDateTime executionStart = run.startedAt() == null ? run.submittedAt() : run.startedAt();
                if (executionStart.plusSeconds(run.maxDurationSeconds()).isAfter(recoveredAt)) {
                    continue;
                }
                entry.setValue(new StoredRun(
                    run.tenantId(),
                    run.runId(),
                    run.runKey(),
                    "FAILED",
                    run.dataDirectory(),
                    run.maxConcurrency(),
                    run.maxTokens(),
                    run.maxCost(),
                    run.maxDurationSeconds(),
                    run.operator(),
                    run.totalSamples(),
                    run.completedSamples(),
                    run.totalTokens(),
                    run.totalCost(),
                    run.reportId(),
                    "RUN_INTERRUPTED",
                    run.submittedAt(),
                    run.startedAt(),
                    recoveredAt
                ));
                recovered++;
            }
            return recovered;
        }

        private String key(long tenantId, String runKey) {
            return tenantId + ":" + runKey;
        }
    }

}
