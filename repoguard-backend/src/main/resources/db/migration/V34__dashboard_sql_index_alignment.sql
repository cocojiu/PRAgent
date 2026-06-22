alter table review_task
    add key idx_review_task_dashboard_created_risk (created_at, risk_level),
    add key idx_review_task_dashboard_created_llm_model (created_at, llm_status, llm_provider, llm_model),
    add key idx_review_task_dashboard_created_llm_repo (created_at, llm_status, organization, repository);

alter table review_finding
    add key idx_review_finding_task_category_rule (task_id, category, rule_id);
