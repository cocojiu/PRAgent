-- Aggregate runtime release observations. No prompts, source, provider payloads or credentials are stored.
alter table notification_event modify task_id bigint null;
alter table notification_delivery_log modify task_id bigint null;

create table llm_model_release_metric_snapshot (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    release_id bigint not null,
    release_key varchar(128) not null,
    provider varchar(64) not null,
    model_name varchar(128) not null,
    window_start datetime(6) not null,
    window_end datetime(6) not null,
    sample_count bigint unsigned not null default 0,
    total_tokens bigint unsigned not null default 0,
    total_cost decimal(18,8) not null default 0,
    p95_latency_ms bigint unsigned not null default 0,
    parse_failure_count bigint unsigned not null default 0,
    fallback_count bigint unsigned not null default 0,
    rollback_count bigint unsigned not null default 0,
    alert_state varchar(32) not null default 'INSUFFICIENT_SAMPLE',
    alert_codes varchar(1024) not null default '',
    action varchar(32) not null default 'NONE',
    alert_fingerprint char(64) not null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_release_metric_window (tenant_id, release_id, window_start, window_end),
    unique key uk_llm_release_metric_id (tenant_id, id),
    key idx_llm_release_metric_query (tenant_id, release_key, window_start, id),
    key idx_llm_release_metric_alert (tenant_id, alert_state, window_start),
    constraint fk_llm_release_metric_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_llm_release_metric_release foreign key (tenant_id, release_id)
        references llm_model_release(tenant_id, id),
    constraint chk_llm_release_metric_window check (window_end > window_start),
    constraint chk_llm_release_metric_alert_fingerprint check (alert_fingerprint regexp '^[0-9a-fA-F]{64}$'),
    constraint chk_llm_release_metric_state check (alert_state in ('NORMAL', 'INSUFFICIENT_SAMPLE', 'ALERT', 'AUTO_ROLLBACK')),
    constraint chk_llm_release_metric_action check (action in ('NONE', 'NOTIFY', 'AUTO_ROLLBACK'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
