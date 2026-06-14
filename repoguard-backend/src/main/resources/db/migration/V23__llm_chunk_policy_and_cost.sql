alter table review_policy_config
    add column chunk_file_threshold int not null default 6 after worker_concurrency,
    add column chunk_line_threshold int not null default 700 after chunk_file_threshold,
    add column chunk_max_files int not null default 4 after chunk_line_threshold,
    add column chunk_max_lines int not null default 450 after chunk_max_files,
    add column input_token_price_per_million decimal(10, 4) not null default 0.0000 after chunk_max_lines,
    add column output_token_price_per_million decimal(10, 4) not null default 0.0000 after input_token_price_per_million;

alter table review_task
    add column llm_prompt_tokens int null after llm_prompt_summary,
    add column llm_completion_tokens int null after llm_prompt_tokens,
    add column llm_total_tokens int null after llm_completion_tokens,
    add column llm_estimated_cost decimal(12, 6) null after llm_total_tokens,
    add key idx_review_task_llm_cost (llm_model, llm_total_tokens);
