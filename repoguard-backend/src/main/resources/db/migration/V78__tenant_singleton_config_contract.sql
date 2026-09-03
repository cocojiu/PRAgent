-- Contract each tenant to exactly one review-policy row and one system-settings row.
-- The guard fails before DDL when historical data is missing or duplicated so operators can
-- repair ownership explicitly instead of silently choosing an arbitrary configuration.

drop table if exists flyway_v78_tenant_singleton_config_violation;

create table flyway_v78_tenant_singleton_config_violation (
    invariant_name varchar(128) not null,
    violation_count bigint unsigned not null,
    primary key (invariant_name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into flyway_v78_tenant_singleton_config_violation (invariant_name, violation_count)
select 'review_policy_config.duplicate_tenant', coalesce(sum(grouped.row_count - 1), 0)
  from (
      select count(*) as row_count
        from review_policy_config
       group by tenant_id
      having count(*) > 1
  ) grouped
union all
select 'review_policy_config.missing_tenant', count(*)
  from tenant t
  left join review_policy_config config on config.tenant_id = t.id
 where config.id is null
union all
select 'system_settings_config.duplicate_tenant', coalesce(sum(grouped.row_count - 1), 0)
  from (
      select count(*) as row_count
        from system_settings_config
       group by tenant_id
      having count(*) > 1
  ) grouped
union all
select 'system_settings_config.missing_tenant', count(*)
  from tenant t
  left join system_settings_config config on config.tenant_id = t.id
 where config.id is null;

alter table flyway_v78_tenant_singleton_config_violation
    add constraint chk_v78_tenant_singleton_config_clean check (violation_count = 0);

drop table flyway_v78_tenant_singleton_config_violation;

alter table review_policy_config
    add unique key uk_review_policy_config_tenant (tenant_id);

alter table system_settings_config
    add unique key uk_system_settings_config_tenant (tenant_id);
