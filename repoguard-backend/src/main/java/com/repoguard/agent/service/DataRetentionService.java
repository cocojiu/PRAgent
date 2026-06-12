package com.repoguard.agent.service;

import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;

public interface DataRetentionService {

    DataRetentionCleanupResponse cleanup(DataRetentionCleanupRequest request);
}
