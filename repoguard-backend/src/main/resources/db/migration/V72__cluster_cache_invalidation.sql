create table tenant_cache_version (
    tenant_id bigint not null,
    cache_version bigint unsigned not null default 1,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (tenant_id),
    constraint fk_tenant_cache_version_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade,
    constraint chk_tenant_cache_version_positive check (cache_version > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
