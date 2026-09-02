package com.repoguard.agent.review.quality;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records a failed repair in a new transaction after the mutation transaction is rolled back. */
@Service
final class LlmModelReleaseDriftFailureRecorder {

    private final LlmModelReleaseDriftAuditRepository auditRepository;

    LlmModelReleaseDriftFailureRecorder(LlmModelReleaseDriftAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    LlmModelReleaseDriftAuditRepository.StoredAudit record(long tenantId, String operationKey, String failureCode) {
        return auditRepository.fail(tenantId, operationKey, failureCode);
    }
}
