package com.repoguard.agent.service.impl;

import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OperationalDataRetentionBatchExecutor {

    private final OperationalDataRetentionMapper mapper;

    OperationalDataRetentionBatchExecutor(OperationalDataRetentionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteAndAudit(String table, LocalDateTime cutoff, IntSupplier deletion) {
        int deleted = deletion.getAsInt();
        mapper.insertAudit(table, cutoff, deleted, "SUCCESS", null);
        return deleted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String table, LocalDateTime cutoff, RuntimeException failure) {
        mapper.insertAudit(table, cutoff, 0, "FAILED", failure.getClass().getSimpleName());
    }
}
