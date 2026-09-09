-- Durable aggregate-only execution state for asynchronous LLM evaluation runs.
-- Raw PR content, prompts and provider responses remain outside the database.
create table llm_evaluation_run (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    run_id varchar(64) not null,
    run_key varchar(128) not null,
    status varchar(16) not null,
    data_directory varchar(512) not null,
    max_concurrency int not null,
    max_tokens bigint not null,
    max_cost decimal(20, 8) not null,
    max_duration_seconds int not null,
    operator varchar(128) not null,
    total_samples int not null default 0,
    completed_samples int not null default 0,
    total_tokens bigint not null default 0,
    total_cost decimal(20, 8) not null default 0,
    report_id bigint null,
    failure_code varchar(64) null,
    submitted_at datetime(6) not null,
    started_at datetime(6) null,
    finished_at datetime(6) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_evaluation_run_tenant_id (tenant_id, run_id),
    unique key uk_llm_evaluation_run_tenant_key (tenant_id, run_key),
    key idx_llm_evaluation_run_tenant_status (tenant_id, status, submitted_at, id),
    key idx_llm_evaluation_run_report (tenant_id, report_id),
    constraint fk_llm_evaluation_run_tenant
        foreign key (tenant_id) references tenant(id),
    constraint fk_llm_evaluation_run_report
        foreign key (tenant_id, report_id) references llm_evaluation_report(tenant_id, id),
    constraint chk_llm_evaluation_run_status
        check (status in ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')),
    constraint chk_llm_evaluation_run_budget
        check (
            max_concurrency between 1 and 8
            and max_tokens between 1 and 1000000
            and max_cost >= 0
            and max_duration_seconds between 1 and 3600
        ),
    constraint chk_llm_evaluation_run_progress
        check (
            total_samples between 0 and 100
            and completed_samples between 0 and total_samples
            and total_tokens >= 0
            and total_cost >= 0
        )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
