-- Versioned finding identity and cross-attempt comparison metadata.
-- Existing rows remain visible. Their fingerprints and comparison state are
-- calculated by the comparison service on first use so the Java and database
-- normalisation rules cannot drift during migration.
alter table review_finding
    add column finding_fingerprint char(64) null after attempt_id,
    add column previous_finding_id bigint null after finding_fingerprint,
    add column comparison_status varchar(16) null after previous_finding_id,
    add column comparison_confidence decimal(5, 4) null after comparison_status,
    add column comparison_reason varchar(128) null after comparison_confidence,
    add column comparison_version varchar(32) null after comparison_reason,
    add column comparison_attempt_id bigint null after comparison_version,
    add key idx_review_finding_attempt_fingerprint (tenant_id, attempt_id, finding_fingerprint),
    add key idx_review_finding_task_comparison (tenant_id, task_id, comparison_status),
    add key idx_review_finding_comparison_attempt (tenant_id, comparison_attempt_id),
    add constraint fk_review_finding_previous
        foreign key (tenant_id, previous_finding_id)
        references review_finding(tenant_id, id) on delete
        set null,
    add constraint fk_review_finding_comparison_attempt
        foreign key (tenant_id, comparison_attempt_id)
        references review_execution_attempt(tenant_id, id) on delete
        set null;
