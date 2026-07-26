package com.repoguard.agent.service.impl;

import com.repoguard.agent.retention.DataRetentionArchiveWriter;
import com.repoguard.agent.retention.DataRetentionDeleteExecutor;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataRetentionCleanupSliceExecutor {

    private final DataRetentionArchiveWriter archiveWriter;
    private final DataRetentionDeleteExecutor deleteExecutor;

    public DataRetentionCleanupSliceExecutor(
        DataRetentionArchiveWriter archiveWriter,
        DataRetentionDeleteExecutor deleteExecutor
    ) {
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.deleteExecutor = Objects.requireNonNull(deleteExecutor, "deleteExecutor");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataRetentionDeleteExecutor.DeletionResult archiveAndDelete(
        long cleanupBatchId,
        String backupReference,
        List<Long> taskIds
    ) {
        archiveWriter.write(cleanupBatchId, backupReference, taskIds);
        return deleteExecutor.delete(taskIds);
    }
}
