-- Durable SARIF import identity and source linkage for attempt-scoped retries.
create table sarif_import_batch (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    task_id bigint not null,
    attempt_id bigint not null,
    tool_name varchar(128) not null,
    tool_version varchar(64) not null default '',
    commit_sha varchar(64) not null,
    content_fingerprint char(64) not null,
    status varchar(16) not null default 'ACTIVE',
    imported_count int not null default 0,
    skipped_count int not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    unique key uk_sarif_batch_tenant_id (tenant_id, id),
    unique key uk_sarif_batch_identity (
        tenant_id, task_id, attempt_id, tool_name, commit_sha, content_fingerprint
    ),
    key idx_sarif_batch_attempt_status (
        tenant_id, task_id, attempt_id, tool_name, commit_sha, status, created_at
    ),
    constraint fk_sarif_batch_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_sarif_batch_task foreign key (tenant_id, task_id)
        references review_task(tenant_id, id) on delete
        cascade,
    constraint fk_sarif_batch_attempt foreign key (tenant_id, attempt_id)
        references review_execution_attempt(tenant_id, id) on delete
        cascade,
    constraint chk_sarif_batch_status check (status in ('ACTIVE', 'SUPERSEDED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table review_finding
    add column source_batch_id bigint null after attempt_id,
    add key idx_review_finding_tenant_source_batch (tenant_id, source_batch_id),
    add constraint fk_review_finding_source_batch
        foreign key (tenant_id, source_batch_id)
        references sarif_import_batch(tenant_id, id) on delete
        cascade;
