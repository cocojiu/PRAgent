-- CI-native SARIF upload metadata. The payload itself remains in review_finding
-- and is linked through sarif_import_batch; this table records the short-lived CI identity.
create table sarif_ci_upload (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    task_id bigint not null,
    attempt_id bigint not null,
    batch_id bigint not null,
    tool_name varchar(128) not null,
    tool_version varchar(64) not null default '',
    scan_run_id varchar(128) not null,
    commit_sha varchar(64) not null,
    sarif_fingerprint char(64) not null,
    completion_time datetime not null,
    status varchar(16) not null default 'ACTIVE',
    imported_count int not null default 0,
    skipped_count int not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    unique key uk_sarif_ci_upload_tenant_id (tenant_id, id),
    unique key uk_sarif_ci_upload_identity (
        tenant_id, task_id, attempt_id, tool_name, tool_version, commit_sha, scan_run_id
    ),
    key idx_sarif_ci_upload_batch (tenant_id, batch_id),
    key idx_sarif_ci_upload_attempt (tenant_id, task_id, attempt_id, commit_sha, created_at),
    constraint fk_sarif_ci_upload_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_sarif_ci_upload_task foreign key (tenant_id, task_id)
        references review_task(tenant_id, id),
    constraint fk_sarif_ci_upload_attempt foreign key (tenant_id, attempt_id)
        references review_execution_attempt(tenant_id, id),
    constraint fk_sarif_ci_upload_batch foreign key (tenant_id, batch_id)
        references sarif_import_batch(tenant_id, id),
    constraint chk_sarif_ci_upload_status check (status in ('ACTIVE', 'REPLACED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
