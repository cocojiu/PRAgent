package com.repoguard.agent.service;

import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.dto.PageResponse;

public interface DataRetentionService {

    DataRetentionCleanupResponse cleanup(DataRetentionCleanupRequest request);

    PageResponse<DataRetentionCleanupAuditDto> listCleanupAudits(
        int page,
        int pageSize,
        String mode,
        String status,
        String backupReference
    );
}
