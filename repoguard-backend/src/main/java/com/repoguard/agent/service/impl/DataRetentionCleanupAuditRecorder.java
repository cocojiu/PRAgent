package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.entity.DataRetentionCleanupAudit;
import com.repoguard.agent.mapper.DataRetentionCleanupAuditMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataRetentionCleanupAuditRecorder {

    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 512;

    private final DataRetentionCleanupAuditMapper auditMapper;

    public DataRetentionCleanupAuditRecorder(DataRetentionCleanupAuditMapper auditMapper) {
        this.auditMapper = Objects.requireNonNull(auditMapper, "auditMapper");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(
        boolean execute,
        int retentionDays,
        int maxTasks,
        String backupReference,
        LocalDateTime cutoff
    ) {
        LocalDateTime now = LocalDateTime.now();
        DataRetentionCleanupAudit audit = new DataRetentionCleanupAudit();
        audit.setMode(execute ? "execute" : "dry_run");
        audit.setStatus(STATUS_STARTED);
        audit.setRetentionDays(retentionDays);
        audit.setMaxTasks(maxTasks);
        audit.setBackupReference(backupReference);
        audit.setCutoffTime(cutoff);
        audit.setCandidateTasks(0L);
        audit.setSelectedTasks(0);
        audit.setDeletedBatchItems(0);
        audit.setDeletedPublications(0);
        audit.setDeletedBatches(0);
        audit.setDeletedChangedFiles(0);
        audit.setDeletedTimelines(0);
        audit.setDeletedFindings(0);
        audit.setDeletedTasks(0);
        audit.setCreatedAt(now);
        audit.setUpdatedAt(now);
        auditMapper.insert(audit);
        return audit.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long auditId, DataRetentionCleanupResponse response) {
        if (auditId == null || response == null) {
            return;
        }
        DataRetentionCleanupAudit audit = new DataRetentionCleanupAudit();
        audit.setId(auditId);
        audit.setStatus(STATUS_COMPLETED);
        audit.setCandidateTasks(response.candidateTasks());
        audit.setSelectedTasks(response.selectedTasks());
        audit.setDeletedBatchItems(response.deletedBatchItems());
        audit.setDeletedPublications(response.deletedPublications());
        audit.setDeletedBatches(response.deletedBatches());
        audit.setDeletedChangedFiles(response.deletedChangedFiles());
        audit.setDeletedTimelines(response.deletedTimelines());
        audit.setDeletedFindings(response.deletedFindings());
        audit.setDeletedTasks(response.deletedTasks());
        audit.setCompletedAt(LocalDateTime.now());
        audit.setUpdatedAt(audit.getCompletedAt());
        auditMapper.updateById(audit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long auditId, RuntimeException ex) {
        if (auditId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        DataRetentionCleanupAudit audit = new DataRetentionCleanupAudit();
        audit.setId(auditId);
        audit.setStatus(STATUS_FAILED);
        audit.setFailureReason(DataRetentionCleanupFailureClassifier.classify(ex));
        audit.setFailureMessage(truncate(ex == null ? null : ex.getMessage()));
        audit.setCompletedAt(now);
        audit.setUpdatedAt(now);
        auditMapper.updateById(audit);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > FAILURE_MESSAGE_MAX_LENGTH
            ? value.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
            : value;
    }
}
