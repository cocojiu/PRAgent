-- Selective cross-attempt history lookup, including personal mode without tenant SQL rewriting.
-- Keep old indexes for n-1 application compatibility; remove none during this expansion.
ALTER TABLE review_finding
    ADD INDEX idx_review_finding_history_fingerprint
        (task_id, finding_fingerprint, tenant_id, attempt_id),
    ALGORITHM=INPLACE, LOCK=NONE;
