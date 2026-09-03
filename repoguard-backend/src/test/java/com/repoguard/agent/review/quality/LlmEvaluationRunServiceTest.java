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
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
            when(previewRunner.run(any(), anyString(), anyString(), any(ReviewDeadline.class))).thenReturn(observation);
            LlmModelReleaseDto.EvaluationReportDto report = org.mockito.Mockito.mock(LlmModelReleaseDto.EvaluationReportDto.class);
            when(report.id()).thenReturn(77L);
            when(modelReleaseService.createEvaluationReport(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(report);

            LlmEvaluationRunService service = new LlmEvaluationRunService(
                datasetLoader, previewRunner, modelReleaseService, executor
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
            verify(modelReleaseService).createEvaluationReport(
                eq(dataset.version()), eq(dataset.metadata()), any(), anyInt(), anyString()
            );
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
                datasetLoader, previewRunner, modelReleaseService, executor
            );
            LlmEvaluationRunDto invalid = service.start(request("invalid", 100, "1.00"), "operator");
            assertThat(await(service, invalid.runId(), "FAILED").failureCode()).isEqualTo("DATASET_INVALID");

            when(previewRunner.run(any(), anyString(), anyString(), any(ReviewDeadline.class)))
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
            when(previewRunner.run(any(), anyString(), anyString(), any(ReviewDeadline.class))).thenAnswer(invocation -> {
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
                datasetLoader, previewRunner, modelReleaseService, executor
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
            datasetLoader, previewRunner, modelReleaseService, executor
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

}
