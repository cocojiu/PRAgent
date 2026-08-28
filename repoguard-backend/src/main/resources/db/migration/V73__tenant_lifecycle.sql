alter table tenant
    add column status_version bigint unsigned not null default 1 after status,
    add column status_reason varchar(512) null after status_version,
    add column status_changed_at datetime(6) not null default current_timestamp(6) after status_reason,
    add constraint chk_tenant_status check (status in ('ACTIVE', 'SUSPENDED')),
    add constraint chk_tenant_status_version_positive check (status_version > 0);
