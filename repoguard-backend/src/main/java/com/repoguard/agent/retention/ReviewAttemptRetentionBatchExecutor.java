package com.repoguard.agent.retention;

import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReviewAttemptRetentionBatchExecutor {

    private final ReviewExecutionAttemptMapper mapper;

    ReviewAttemptRetentionBatchExecutor(ReviewExecutionAttemptMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgePayload(List<Long> attemptIds, LocalDateTime purgedAt) {
        if (attemptIds.isEmpty()) {
            return 0;
        }
        mapper.deleteChangedFilesByAttemptIds(attemptIds);
        mapper.deleteFindingsByAttemptIds(attemptIds);
        return mapper.markPayloadPurged(attemptIds, purgedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteMetadata(LocalDateTime cutoff, int limit) {
        return mapper.deleteHistoricalAttemptMetadata(cutoff, limit);
    }
}
