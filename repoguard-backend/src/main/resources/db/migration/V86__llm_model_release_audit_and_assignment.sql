-- Persist the model assignment captured at review start and append-only release transitions.
alter table review_task
    add column llm_release_key varchar(128) null after llm_model,
    add key idx_review_task_llm_release (tenant_id, llm_release_key);

create table llm_model_release_audit (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    release_id bigint not null,
    release_key varchar(128) not null,
    action varchar(32) not null,
    from_state varchar(16) null,
    to_state varchar(16) not null,
    traffic_percent tinyint unsigned not null default 0,
    operator varchar(128) not null,
    reason varchar(512) not null default '',
    details_json longtext not null,
    event_hash char(64) not null,
    created_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_model_release_audit_tenant_id (tenant_id, id),
    key idx_llm_model_release_audit_release (tenant_id, release_id, created_at, id),
    key idx_llm_model_release_audit_hash (tenant_id, event_hash),
    constraint fk_llm_model_release_audit_tenant
        foreign key (tenant_id) references tenant(id),
    constraint fk_llm_model_release_audit_release
        foreign key (tenant_id, release_id)
        references llm_model_release(tenant_id, id) on delete
        restrict,
    constraint chk_llm_model_release_audit_action
        check (action in ('REGISTER_SHADOW', 'PROMOTE', 'REPLACE_ACTIVE', 'ROLLBACK', 'AUTO_ROLLBACK')),
    constraint chk_llm_model_release_audit_from_state
        check (from_state is null or from_state in ('SHADOW', 'CANARY', 'ACTIVE', 'ROLLED_BACK')),
    constraint chk_llm_model_release_audit_to_state
        check (to_state in ('SHADOW', 'CANARY', 'ACTIVE', 'ROLLED_BACK')),
    constraint chk_llm_model_release_audit_traffic
        check (traffic_percent between 0 and 100),
    constraint chk_llm_model_release_audit_hash
        check (event_hash regexp '^[0-9a-fA-F]{64}$')
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
