package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.DataRetentionCleanupAudit;
import com.repoguard.agent.mapper.DataRetentionCleanupAuditMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DataRetentionCleanupAuditQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataRetentionCleanupAuditMapper auditMapper;

    public DataRetentionCleanupAuditQueryService(DataRetentionCleanupAuditMapper auditMapper) {
        this.auditMapper = Objects.requireNonNull(auditMapper, "auditMapper");
    }

    public PageResponse<DataRetentionCleanupAuditDto> listAudits(
        int page,
        int pageSize,
        String mode,
        String status,
        String backupReference
    ) {
        Page<DataRetentionCleanupAudit> result = auditMapper.selectPage(
            Page.of(page, pageSize),
            query(mode, status, backupReference)
        );
        return new PageResponse<>(
            result.getRecords().stream().map(this::toDto).toList(),
            result.getTotal()
        );
    }

    private LambdaQueryWrapper<DataRetentionCleanupAudit> query(String mode, String status, String backupReference) {
        LambdaQueryWrapper<DataRetentionCleanupAudit> wrapper = new LambdaQueryWrapper<DataRetentionCleanupAudit>()
            .orderByDesc(DataRetentionCleanupAudit::getCreatedAt)
            .orderByDesc(DataRetentionCleanupAudit::getId);
        if (StringUtils.hasText(mode)) {
            wrapper.eq(DataRetentionCleanupAudit::getMode, mode.trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DataRetentionCleanupAudit::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(backupReference)) {
            wrapper.eq(DataRetentionCleanupAudit::getBackupReference, backupReference.trim());
        }
        return wrapper;
    }

    private DataRetentionCleanupAuditDto toDto(DataRetentionCleanupAudit audit) {
        return new DataRetentionCleanupAuditDto(
            audit.getId(),
            audit.getMode(),
            audit.getStatus(),
            audit.getRetentionDays(),
            audit.getMaxTasks(),
            audit.getBackupReference(),
            format(audit.getCutoffTime()),
            audit.getCandidateTasks(),
            audit.getSelectedTasks(),
            audit.getDeletedBatchItems(),
            audit.getDeletedPublications(),
            audit.getDeletedBatches(),
            audit.getDeletedChangedFiles(),
            audit.getDeletedTimelines(),
            audit.getDeletedFindings(),
            audit.getDeletedTasks(),
            audit.getFailureReason(),
            audit.getFailureMessage(),
            format(audit.getCreatedAt()),
            format(audit.getCompletedAt()),
            format(audit.getUpdatedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
