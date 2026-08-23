-- V45 introduced normalized replacements for these V34 dashboard indexes.
-- Keep the legacy structures for instant rollback, but remove them from the
-- optimizer's candidate set so duplicate write amplification is eliminated in
-- a later, separately observed maintenance window rather than by blind DROP.
alter table review_task
    alter index idx_review_task_dashboard_created_risk invisible,
    alter index idx_review_task_dashboard_created_llm_model invisible,
    alter index idx_review_task_dashboard_created_llm_repo invisible;
