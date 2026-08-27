package com.repoguard.agent.retention;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class ReviewAttemptRetentionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewAttemptRetentionWorker.class);

    private final ReviewExecutionAttemptMapper mapper;
    private final ReviewAttemptRetentionBatchExecutor executor;
    private final OperationalDataRetentionProperties properties;
    private final MeterRegistry meterRegistry;

    public ReviewAttemptRetentionWorker(
        ReviewExecutionAttemptMapper mapper,
        ReviewAttemptRetentionBatchExecutor executor,
        OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public void cleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        purgeHistoricalPayload();
        deleteHistoricalMetadata();
    }

    private void purgeHistoricalPayload() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.normalizedReviewAttemptPayloadDays());
        int limit = properties.normalizedBatchSize();
        for (int batch = 0; batch < properties.normalizedMaxBatchesPerRun(); batch++) {
            try {
                List<Long> candidates = mapper.selectPayloadPurgeCandidates(cutoff, limit);
                if (candidates.isEmpty()) {
                    return;
                }
                int purged = executor.purgePayload(candidates, LocalDateTime.now());
                meterRegistry.counter("repoguard.review.attempt.retention", "operation", "payload_purged").increment(purged);
                if (candidates.size() < limit) {
                    return;
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("repoguard.review.attempt.retention", "operation", "payload_failed").increment();
                LOGGER.error("Review attempt payload retention failed", exception);
                return;
            }
        }
        meterRegistry.counter("repoguard.review.attempt.retention", "operation", "payload_backlog").increment();
    }

    private void deleteHistoricalMetadata() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.normalizedReviewAttemptMetadataDays());
        int limit = properties.normalizedBatchSize();
        for (int batch = 0; batch < properties.normalizedMaxBatchesPerRun(); batch++) {
            try {
                int deleted = executor.deleteMetadata(cutoff, limit);
                meterRegistry.counter("repoguard.review.attempt.retention", "operation", "metadata_deleted").increment(deleted);
                if (deleted < limit) {
                    return;
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("repoguard.review.attempt.retention", "operation", "metadata_failed").increment();
                LOGGER.error("Review attempt metadata retention failed", exception);
                return;
            }
        }
        meterRegistry.counter("repoguard.review.attempt.retention", "operation", "metadata_backlog").increment();
    }
}
