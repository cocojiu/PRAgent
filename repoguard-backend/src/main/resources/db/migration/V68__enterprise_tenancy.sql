-- Enterprise tenant boundary. Existing installations are backfilled into the
-- immutable default tenant so the migration is backward compatible.
create table tenant (
    id bigint primary key auto_increment,
    tenant_key varchar(64) not null,
    display_name varchar(128) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    unique key uk_tenant_key (tenant_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into tenant (id, tenant_key, display_name, status)
values (1, 'default', 'Default tenant', 'ACTIVE');

create table tenant_membership (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    user_id bigint not null,
    role varchar(32) not null,
    default_tenant tinyint(1) not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    unique key uk_tenant_membership_tenant_user (tenant_id, user_id),
    key idx_tenant_membership_user_default (user_id, default_tenant, tenant_id),
    constraint fk_tenant_membership_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_tenant_membership_user foreign key (user_id) references user_account(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into tenant_membership (tenant_id, user_id, role, default_tenant)
select 1, id, role, 1 from user_account;

create table tenant_repository (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    organization varchar(128) not null,
    repository varchar(128) not null,
    github_installation_id bigint unsigned null,
    status varchar(32) not null default 'ACTIVE',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    unique key uk_tenant_repository_name (organization, repository),
    unique key uk_tenant_repository_installation (github_installation_id),
    key idx_tenant_repository_tenant (tenant_id, status),
    constraint fk_tenant_repository_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into tenant_repository (tenant_id, organization, repository)
select 1, trim(default_owner), trim(default_repo)
from integration_config
where provider = 'GITHUB'
  and default_owner is not null and trim(default_owner) <> ''
  and default_repo is not null and trim(default_repo) <> ''
on duplicate key update tenant_id = values(tenant_id);

create table enterprise_identity (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    user_id bigint not null,
    issuer varchar(512) not null,
    subject varchar(255) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    unique key uk_enterprise_identity_subject (issuer(191), subject),
    unique key uk_enterprise_identity_tenant_user (tenant_id, user_id),
    key idx_enterprise_identity_user (user_id, status),
    constraint fk_enterprise_identity_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_enterprise_identity_user foreign key (user_id) references user_account(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table review_task
    add column tenant_id bigint not null default 1 after id,
    drop key uk_review_task_pr_commit,
    add unique key uk_review_task_tenant_pr_commit (tenant_id, organization, repository, pr_number, commit_sha),
    add key idx_review_task_tenant_created (tenant_id, created_at, id);

alter table changed_file
    add column tenant_id bigint not null default 1 after id,
    add key idx_changed_file_tenant_task (tenant_id, task_id);

alter table review_finding
    add column tenant_id bigint not null default 1 after id,
    add key idx_review_finding_tenant_task (tenant_id, task_id);

alter table review_timeline
    add column tenant_id bigint not null default 1 after id,
    add key idx_review_timeline_tenant_task (tenant_id, task_id);

alter table review_pull_request_head
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, organization, repository, pr_number);

alter table review_execution_attempt
    add column tenant_id bigint not null default 1 after id,
    add key idx_review_execution_attempt_tenant_task (tenant_id, task_id);

alter table github_comment_publication
    add column tenant_id bigint not null default 1 after id,
    add key idx_github_comment_publication_tenant_task (tenant_id, task_id);

alter table github_comment_publication_batch
    add column tenant_id bigint not null default 1 after id,
    add key idx_github_comment_batch_tenant_task (tenant_id, task_id);

alter table github_comment_publication_batch_item
    add column tenant_id bigint not null default 1 after id,
    add key idx_github_comment_item_tenant_batch (tenant_id, batch_id);

alter table integration_config
    add column tenant_id bigint not null default 1 after id,
    drop key uk_integration_config_provider,
    add unique key uk_integration_config_tenant_provider (tenant_id, provider);

alter table review_policy_config
    add column tenant_id bigint not null default 1 after id,
    add key idx_review_policy_config_tenant (tenant_id, updated_at);

alter table review_rule_config
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, id),
    add key idx_review_rule_config_tenant_status (tenant_id, status);

alter table review_rule_policy_snapshot
    add column tenant_id bigint not null default 1 after id,
    drop key uk_review_rule_policy_snapshot_version,
    add unique key uk_review_rule_policy_tenant_version (tenant_id, rule_id, policy_version);

alter table review_strategy_policy_snapshot
    add column tenant_id bigint not null default 1 after id,
    drop key uk_review_strategy_policy_single_active,
    add unique key uk_review_strategy_tenant_active (tenant_id, active_guard);

alter table review_policy_promotion_evidence
    add column tenant_id bigint not null default 1 after id,
    add key idx_review_policy_evidence_tenant_created (tenant_id, created_at, id);

alter table review_quality_baseline_snapshot
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, snapshot_key);

alter table system_settings_config
    add column tenant_id bigint not null default 1 after id,
    add key idx_system_settings_tenant (tenant_id, updated_at);

alter table system_setting_log
    add column tenant_id bigint not null default 1 after id,
    add key idx_system_setting_log_tenant_created (tenant_id, created_at);

alter table notification_channel_binding
    add column tenant_id bigint not null default 1 after id,
    add key idx_notification_binding_tenant_repo (tenant_id, organization, repository);

alter table notification_event
    add column tenant_id bigint not null default 1 after id,
    drop key uk_notification_event_key,
    add unique key uk_notification_event_tenant_key (tenant_id, event_key),
    add key idx_notification_event_tenant_status (tenant_id, status, next_retry_at);

alter table notification_delivery_log
    add column tenant_id bigint not null default 1 after id,
    add key idx_notification_delivery_tenant_event (tenant_id, event_id);

alter table review_repository_dimension
    add column tenant_id bigint not null default 1 after id,
    drop key uk_review_repository_dimension_org_repo,
    add unique key uk_review_repository_tenant_org_repo (tenant_id, organization, repository);

alter table review_task_archive_summary
    add column tenant_id bigint not null default 1 after id,
    drop key uk_review_task_archive_summary_task,
    add unique key uk_review_task_archive_tenant_task (tenant_id, task_id);

alter table dashboard_review_daily_stat
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, stat_date);

alter table dashboard_rule_daily_stat
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, stat_date, rule_id);

alter table dashboard_llm_quality_daily_stat
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, stat_date, model_label, repository_label);

alter table dashboard_daily_snapshot_refresh_state
    add column tenant_id bigint not null default 1 first,
    drop primary key,
    add primary key (tenant_id, stat_date);
