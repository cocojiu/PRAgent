package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmModelReleaseMetricsServiceTest {

    private final LlmModelReleaseRepository releaseRepository = org.mockito.Mockito.mock(LlmModelReleaseRepository.class);
    private final LlmModelReleaseMetricsRepository metricsRepository = org.mockito.Mockito.mock(LlmModelReleaseMetricsRepository.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final LlmModelReleaseRuntimeSupport runtimeSupport = org.mockito.Mockito.mock(LlmModelReleaseRuntimeSupport.class);
    private final LlmModelReleaseAlertPublisher alertPublisher = org.mockito.Mockito.mock(LlmModelReleaseAlertPublisher.class);
    private final LlmModelReleaseMetricsService service = new LlmModelReleaseMetricsService(
        releaseRepository, metricsRepository, metrics, runtimeSupport, alertPublisher
    );

    @Test
    void keepsInsufficientWindowsQuietAndUsesTheActiveTenant() {
        LocalDateTime end = LocalDateTime.of(2026, 9, 3, 2, 0);
        LlmModelReleaseDto release = release("ACTIVE");
        when(releaseRepository.findAll(42L)).thenReturn(List.of(release));
        when(metricsRepository.aggregate(42L, "release-next", end.minusHours(1), end))
            .thenReturn(new LlmModelReleaseMetricsRepository.RuntimeAggregate(
                9L, 900L, new BigDecimal("0.09"), 800L, 0L, 0L
            ));
        when(metricsRepository.countRollbacks(42L, 7L, end.minusHours(1), end)).thenReturn(0L);
        when(metricsRepository.findOne(7L, end.minusHours(1), end)).thenReturn(null);
        when(metricsRepository.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        try (TenantContext.Scope _ = TenantContext.withTenant(42L)) {
            var result = service.collectWindow(end);

            assertThat(result).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.alertState()).isEqualTo("INSUFFICIENT_SAMPLE");
                assertThat(snapshot.action()).isEqualTo("NONE");
                assertThat(snapshot.alertCodes()).containsExactly("SAMPLE_COUNT_BELOW_10");
                assertThat(snapshot.alertFingerprint()).hasSize(64).matches("[0-9a-f]{64}");
            });
        }

        verify(releaseRepository).findAll(42L);
        verify(alertPublisher, never()).publish(any());
        verify(metrics).llmReleaseSnapshot(eq("release-next"), eq("openai"), eq("gpt-next"), eq(9L), eq(900L),
            eq(new BigDecimal("0.09")), eq(800L),
            org.mockito.ArgumentMatchers.argThat(value -> value.signum() == 0),
            org.mockito.ArgumentMatchers.argThat(value -> value.signum() == 0),
            eq("INSUFFICIENT_SAMPLE"));
    }

    @Test
    void explainsCanaryAnomaliesAndRollsBackOnce() {
        LocalDateTime end = LocalDateTime.of(2026, 9, 3, 2, 0);
        LlmModelReleaseDto release = release("CANARY");
        when(releaseRepository.findAll(1L)).thenReturn(List.of(release));
        when(metricsRepository.aggregate(1L, "release-next", end.minusHours(1), end))
            .thenReturn(new LlmModelReleaseMetricsRepository.RuntimeAggregate(
                10L, 1000L, new BigDecimal("20.00"), 20_000L, 1L, 3L
            ));
        when(metricsRepository.countRollbacks(1L, 7L, end.minusHours(1), end)).thenReturn(1L);
        when(metricsRepository.findOne(7L, end.minusHours(1), end)).thenReturn(null);
        when(metricsRepository.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(releaseRepository.rollback(1L, 7L, "运行指标自动回滚: PARSE_FAILURE_RATE_ABOVE_5, FALLBACK_RATE_ABOVE_20, P95_LATENCY_ABOVE_RUNTIME_THRESHOLD, COST_ABOVE_150_PERCENT, ROLLBACK_OBSERVED"))
            .thenReturn(1);
        when(releaseRepository.findById(1L, 7L)).thenReturn(release("ROLLED_BACK"));

        var result = service.collectWindow(end);

        assertThat(result).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.alertState()).isEqualTo("AUTO_ROLLBACK");
            assertThat(snapshot.action()).isEqualTo("AUTO_ROLLBACK");
            assertThat(snapshot.alertCodes()).containsExactly(
                "PARSE_FAILURE_RATE_ABOVE_5",
                "FALLBACK_RATE_ABOVE_20",
                "P95_LATENCY_ABOVE_RUNTIME_THRESHOLD",
                "COST_ABOVE_150_PERCENT",
                "ROLLBACK_OBSERVED"
            );
        });
        ArgumentCaptor<LlmModelReleaseMetricSnapshot> snapshotCaptor = ArgumentCaptor.forClass(LlmModelReleaseMetricSnapshot.class);
        verify(alertPublisher).publish(snapshotCaptor.capture());
        verify(releaseRepository).rollback(1L, 7L, "运行指标自动回滚: PARSE_FAILURE_RATE_ABOVE_5, FALLBACK_RATE_ABOVE_20, P95_LATENCY_ABOVE_RUNTIME_THRESHOLD, COST_ABOVE_150_PERCENT, ROLLBACK_OBSERVED");
        verify(runtimeSupport).audit(1L, "AUTO_ROLLBACK", release, releaseRepository.findById(1L, 7L), "system",
            snapshotCaptor.getValue().alertCodes().toString());
    }

    @Test
    void repeatedCollectionDoesNotRepublishTheSameWindow() {
        LocalDateTime end = LocalDateTime.of(2026, 9, 3, 2, 0);
        LlmModelReleaseDto release = release("ACTIVE");
        when(releaseRepository.findAll(1L)).thenReturn(List.of(release));
        when(metricsRepository.aggregate(1L, "release-next", end.minusHours(1), end))
            .thenReturn(new LlmModelReleaseMetricsRepository.RuntimeAggregate(
                10L, 1000L, new BigDecimal("20.00"), 20_000L, 1L, 3L
            ));
        when(metricsRepository.countRollbacks(1L, 7L, end.minusHours(1), end)).thenReturn(0L);
        when(metricsRepository.findOne(7L, end.minusHours(1), end)).thenReturn(null);
        when(metricsRepository.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.collectWindow(end);
        ArgumentCaptor<LlmModelReleaseMetricSnapshot> firstCaptor = ArgumentCaptor.forClass(LlmModelReleaseMetricSnapshot.class);
        verify(metricsRepository).upsert(firstCaptor.capture());
        when(metricsRepository.findOne(7L, end.minusHours(1), end)).thenReturn(firstCaptor.getValue());

        service.collectWindow(end);

        verify(alertPublisher, times(1)).publish(any());
        assertThat(firstCaptor.getValue().alertFingerprint()).hasSize(64);
    }

    private LlmModelReleaseDto release(String state) {
        return new LlmModelReleaseDto(
            7L, "release-next", "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1",
            "dataset-1", "v1", "fingerprint", state, 10, true,
            new BigDecimal("0.95"), new BigDecimal("0.85"), new BigDecimal("0.98"), new BigDecimal("0.01"),
            new BigDecimal("0.01"), 1_000L, BigDecimal.ONE, 1_000L, List.of(), null, "tester",
            LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 3, 0, 0), 77L
        );
    }
}
