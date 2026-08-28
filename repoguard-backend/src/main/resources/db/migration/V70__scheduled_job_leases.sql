-- Explicit cross-replica leases for the centralized scheduled entrypoint.
-- The deterministic scope key supports both tenant jobs and the small number
-- of intentionally global maintenance operations.
create table scheduled_job_lease (
    scope_key varchar(191) primary key,
    tenant_id bigint null,
    job_name varchar(128) not null,
    owner_id varchar(36) null,
    locked_until datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    key idx_scheduled_job_lease_tenant_until (tenant_id, locked_until),
    constraint fk_scheduled_job_lease_tenant
        foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
