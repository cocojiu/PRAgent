create table tenant_quota_config (
    tenant_id bigint not null,
    quota_version bigint unsigned not null default 1,
    max_daily_reviews int unsigned not null default 1000,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (tenant_id),
    constraint fk_tenant_quota_config_tenant
        foreign key (tenant_id) references tenant(id),
    constraint chk_tenant_quota_version_positive check (quota_version > 0),
    constraint chk_tenant_quota_daily_reviews_positive check (max_daily_reviews > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into tenant_quota_config (tenant_id, quota_version, max_daily_reviews)
select id, 1, 1000
  from tenant
on duplicate key update tenant_id = values(tenant_id);

create table tenant_quota_usage (
    tenant_id bigint not null,
    usage_date date not null,
    review_count int unsigned not null default 0,
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (tenant_id, usage_date),
    constraint fk_tenant_quota_usage_tenant
        foreign key (tenant_id) references tenant(id),
    constraint chk_tenant_quota_usage_review_count_nonnegative check (review_count >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
