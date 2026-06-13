alter table review_task
    add column llm_provider varchar(64) null after llm_status,
    add column llm_model varchar(128) null after llm_provider,
    add column llm_duration_ms int null after llm_model,
    add column llm_parse_status varchar(32) null after llm_duration_ms,
    add column llm_fallback_reason varchar(512) null after llm_parse_status,
    add column llm_prompt_summary varchar(1024) null after llm_fallback_reason,
    add key idx_review_task_llm_quality (llm_status, llm_parse_status, llm_model);
