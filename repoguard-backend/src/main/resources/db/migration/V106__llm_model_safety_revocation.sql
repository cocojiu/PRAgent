-- Ordinary rollback retains deterministic assignment; this separate flag stops new sends.
alter table llm_model_release
    add column safety_revoked_at datetime(6) null,
    add column safety_revoke_reason varchar(512) null;

-- Widen the action set in one atomic DDL statement, preserving all existing actions.
alter table llm_model_release_audit
    drop check chk_llm_model_release_audit_action,
    add constraint chk_llm_model_release_audit_action
        check (action in ('REGISTER_SHADOW', 'PROMOTE', 'REPLACE_ACTIVE', 'ROLLBACK', 'AUTO_ROLLBACK', 'SAFETY_REVOKE'));
