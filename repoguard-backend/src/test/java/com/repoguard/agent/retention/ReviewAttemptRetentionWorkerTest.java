package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewAttemptRetentionWorkerTest {

    private final ReviewExecutionAttemptMapper mapper = org.mockito.Mockito.mock(
        ReviewExecutionAttemptMapper.class
    );
    private final ReviewAttemptRetentionBatchExecutor executor = org.mockito.Mockito.mock(
        ReviewAttemptRetentionBatchExecutor.class
    );
    private final OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ReviewAttemptRetentionWorker worker = new ReviewAttemptRetentionWorker(
        mapper,
        executor,
        properties,
        meterRegistry
    );

    @Test
    void disabledRetentionLeavesHistoricalAttemptsUntouched() {
        properties.setEnabled(false);

        worker.cleanup();

        verifyNoInteractions(mapper, executor);
    }

    @Test
    void purgesPayloadAndMetadataInBoundedBatches() {
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(3);
        when(mapper.selectPayloadPurgeCandidates(any(LocalDateTime.class), eq(2)))
            .thenReturn(List.of(1L, 2L))
            .thenReturn(List.of(3L));
        when(executor.purgePayload(eq(List.of(1L, 2L)), any(LocalDateTime.class))).thenReturn(2);
        when(executor.purgePayload(eq(List.of(3L)), any(LocalDateTime.class))).thenReturn(1);
        when(executor.deleteMetadata(any(LocalDateTime.class), eq(2)))
            .thenReturn(2)
            .thenReturn(1);

        worker.cleanup();

        assertThat(counter("payload_purged")).isEqualTo(3.0);
        assertThat(counter("metadata_deleted")).isEqualTo(3.0);
        assertThat(meterRegistry.find("repoguard.review.attempt.retention")
            .tag("operation", "payload_backlog").counter()).isNull();
    }

    @Test
    void recordsBacklogWhenRunBudgetIsExhausted() {
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(1);
        when(mapper.selectPayloadPurgeCandidates(any(LocalDateTime.class), eq(2)))
            .thenReturn(List.of(1L, 2L));
        when(executor.purgePayload(eq(List.of(1L, 2L)), any(LocalDateTime.class))).thenReturn(2);
        when(executor.deleteMetadata(any(LocalDateTime.class), eq(2))).thenReturn(2);

        worker.cleanup();

        assertThat(counter("payload_backlog")).isEqualTo(1.0);
        assertThat(counter("metadata_backlog")).isEqualTo(1.0);
    }

    @Test
    void isolatesPayloadAndMetadataFailures() {
        when(mapper.selectPayloadPurgeCandidates(any(LocalDateTime.class), eq(500)))
            .thenThrow(new IllegalStateException("payload unavailable"));
        when(executor.deleteMetadata(any(LocalDateTime.class), eq(500)))
            .thenThrow(new IllegalStateException("metadata unavailable"));

        worker.cleanup();

        assertThat(counter("payload_failed")).isEqualTo(1.0);
        assertThat(counter("metadata_failed")).isEqualTo(1.0);
    }

    private double counter(String operation) {
        return meterRegistry.get("repoguard.review.attempt.retention")
            .tag("operation", operation)
            .counter()
            .count();
    }
}
