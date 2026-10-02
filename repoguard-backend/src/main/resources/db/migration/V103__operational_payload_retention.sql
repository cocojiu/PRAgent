-- Keep durable idempotency rows; payload purge is explicitly opt-in.
ALTER TABLE github_feedback_event
    ADD COLUMN retention_protected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN payload_purged_at DATETIME(6) NULL,
    ADD KEY idx_feedback_payload_retention (tenant_id, payload_purged_at, status, updated_at, id);

ALTER TABLE llm_evaluation_run
    ADD COLUMN retention_protected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN payload_purged_at DATETIME(6) NULL,
    ADD KEY idx_evaluation_run_payload_retention (tenant_id, payload_purged_at, status, finished_at, id);
