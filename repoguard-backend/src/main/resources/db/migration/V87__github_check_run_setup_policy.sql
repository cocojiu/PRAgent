-- Persist the explicit per-repository Check Run consent without deleting historical runs.
create table if not exists github_check_run_policy (
    id bigint not null auto_increment,
    tenant_id bigint not null default 1,
    organization varchar(255) not null,
    repository varchar(255) not null,
    enabled tinyint(1) not null default 0,
    policy_version bigint unsigned not null default 1,
    updated_by varchar(128) not null default '',
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    unique key uk_github_check_run_policy_tenant_id (tenant_id, id),
    unique key uk_github_check_run_policy_repository (tenant_id, organization, repository),
    key idx_github_check_run_policy_updated (tenant_id, updated_at),
    constraint fk_github_check_run_policy_tenant
        foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
