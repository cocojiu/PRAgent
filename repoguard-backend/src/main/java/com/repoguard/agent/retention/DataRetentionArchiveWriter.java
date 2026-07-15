package com.repoguard.agent.retention;

import com.repoguard.agent.mapper.ReviewTaskArchiveSummaryMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionArchiveWriter {

    private final ReviewTaskArchiveSummaryMapper reviewTaskArchiveSummaryMapper;

    public DataRetentionArchiveWriter(ReviewTaskArchiveSummaryMapper reviewTaskArchiveSummaryMapper) {
        this.reviewTaskArchiveSummaryMapper = Objects.requireNonNull(
            reviewTaskArchiveSummaryMapper,
            "reviewTaskArchiveSummaryMapper"
        );
    }

    public void write(long cleanupBatchId, String backupReference, List<Long> taskIds) {
        Objects.requireNonNull(backupReference, "backupReference");
        List<Long> immutableTaskIds = List.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
        if (immutableTaskIds.isEmpty()) {
            throw new IllegalArgumentException("taskIds must not be empty");
        }
        reviewTaskArchiveSummaryMapper.insertArchiveSummaries(cleanupBatchId, backupReference, immutableTaskIds);
    }
}
