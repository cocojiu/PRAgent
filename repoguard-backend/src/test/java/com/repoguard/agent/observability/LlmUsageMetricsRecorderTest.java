package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.observability.LlmUsageMetricsRecorder.PendingSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LlmUsageMetricsRecorderTest {
    private final Instant now = Instant.parse("2026-10-14T02:00:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmUsageMetricsRecorder metrics = new LlmUsageMetricsRecorder(registry, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void missingSnapshotIsUnknownAndFailedCollectionKeepsPreviousEvidence() {
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isNaN();
        metrics.collected(List.of(new PendingSample("UNKNOWN", 7, now.minusSeconds(3600))));
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(7);
        assertThat(registry.get("repoguard.llm.usage.oldest.age").tag("status", "unknown").gauge().value()).isEqualTo(3600);
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "reserved").gauge().value()).isZero();
        metrics.collectionFailed();
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(7);
        assertThat(registry.get("repoguard.llm.usage.snapshot.last.success").gauge().value()).isEqualTo(now.getEpochSecond());
        assertThat(registry.get("repoguard.llm.usage.snapshot.failed").counter().count()).isEqualTo(1);
        metrics.collected(List.of());
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isZero();
        assertThat(registry.get("repoguard.llm.usage.oldest.age").tag("status", "unknown").gauge().value()).isZero();
    }

    @Test
    void queueWaitUsesUtcAndClampsFutureMessagesWithoutRecordingMissingTimestamps() {
        metrics.queueWait(LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
        metrics.queueWait(LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC));
        metrics.queueWait(null);
        var timer = registry.get("repoguard.review.queue.wait").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(60);
    }

    @Test
    void futureAccountingTimestampsDoNotProduceNegativeAges() {
        metrics.collected(List.of(new PendingSample("LEGACY", 1, now.plusSeconds(10))));
        assertThat(registry.get("repoguard.llm.usage.oldest.age").tag("status", "legacy").gauge().value()).isZero();
    }
}
