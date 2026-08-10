package com.repoguard.agent.retention;

import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataRetentionCleanupSliceExecutor {

    private final DataRetentionArchiveWriter archiveWriter;
    private final DataRetentionDeleteExecutor deleteExecutor;
    private final ReviewQualityBaselineService qualityBaselineService;

    @Autowired
    public DataRetentionCleanupSliceExecutor(
        DataRetentionArchiveWriter archiveWriter,
        DataRetentionDeleteExecutor deleteExecutor,
        ReviewQualityBaselineService qualityBaselineService
    ) {
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.deleteExecutor = Objects.requireNonNull(deleteExecutor, "deleteExecutor");
        this.qualityBaselineService = Objects.requireNonNull(qualityBaselineService, "qualityBaselineService");
    }

    public DataRetentionCleanupSliceExecutor(
        DataRetentionArchiveWriter archiveWriter,
        DataRetentionDeleteExecutor deleteExecutor
    ) {
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.deleteExecutor = Objects.requireNonNull(deleteExecutor, "deleteExecutor");
        this.qualityBaselineService = null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataRetentionDeleteExecutor.DeletionResult archiveAndDelete(
        long cleanupBatchId,
        String backupReference,
        List<Long> taskIds
    ) {
        archiveWriter.write(cleanupBatchId, backupReference, taskIds);
        DataRetentionDeleteExecutor.DeletionResult result = deleteExecutor.delete(taskIds);
        if (qualityBaselineService != null) {
            qualityBaselineService.markDirty();
        }
        return result;
    }
}
