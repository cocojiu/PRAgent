alter table review_task
    add key idx_review_task_llm_model_window (llm_status, created_at, llm_provider, llm_model),
    add key idx_review_task_llm_repository_window (llm_status, created_at, organization, repository);
