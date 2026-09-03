-- Versioned LLM release control plane and tenant monthly budget fields.
-- Release metadata contains only reproducibility and aggregate quality metrics;
-- prompts, source code, provider payloads and credentials stay outside the database.

alter table tenant_quota_config
    add column monthly_llm_token_budget bigint unsigned not null default 0 after max_daily_reviews,
    add column monthly_llm_cost_budget decimal(18,8) not null default 0 after monthly_llm_token_budget,
    add constraint chk_tenant_quota_monthly_token_budget_nonnegative
        check (monthly_llm_token_budget >= 0),
    add constraint chk_tenant_quota_monthly_cost_budget_nonnegative
        check (monthly_llm_cost_budget >= 0);

create table llm_model_release (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    release_key varchar(128) not null,
    provider varchar(64) not null,
    model_name varchar(128) not null,
    prompt_version varchar(96) not null,
    context_version varchar(96) not null,
    schema_version varchar(96) not null,
    dataset_id varchar(128) not null,
    dataset_version varchar(64) not null,
    dataset_fingerprint char(64) not null,
    state varchar(16) not null default 'SHADOW',
    traffic_percent tinyint unsigned not null default 0,
    quality_gate_passed tinyint(1) not null default 0,
    precision_rate decimal(8,6) not null default 0,
    recall_rate decimal(8,6) not null default 0,
    anchor_rate decimal(8,6) not null default 0,
    duplicate_rate decimal(8,6) not null default 0,
    parse_failure_rate decimal(8,6) not null default 0,
    p95_latency_ms bigint unsigned not null default 0,
    average_cost decimal(18,8) not null default 0,
    total_tokens bigint unsigned not null default 0,
    blockers varchar(2000) not null default '',
    rollback_reason varchar(512) null,
    created_by varchar(128) not null default 'system',
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_model_release_tenant_key (tenant_id, release_key),
    unique key uk_llm_model_release_tenant_id (tenant_id, id),
    key idx_llm_model_release_tenant_state (tenant_id, state, updated_at),
    key idx_llm_model_release_tenant_model (tenant_id, provider, model_name, updated_at),
    constraint fk_llm_model_release_tenant
        foreign key (tenant_id) references tenant(id),
    constraint chk_llm_model_release_state
        check (state in ('SHADOW', 'CANARY', 'ACTIVE', 'ROLLED_BACK')),
    constraint chk_llm_model_release_traffic
        check (traffic_percent between 0 and 100),
    constraint chk_llm_model_release_quality_rates
        check (precision_rate between 0 and 1
            and recall_rate between 0 and 1
            and anchor_rate between 0 and 1
            and duplicate_rate between 0 and 1
            and parse_failure_rate between 0 and 1),
    constraint chk_llm_model_release_fingerprint
        check (dataset_fingerprint regexp '^[0-9a-fA-F]{64}$')
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
