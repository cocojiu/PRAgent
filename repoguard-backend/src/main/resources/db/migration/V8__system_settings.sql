create table if not exists system_settings_config (
    id bigint primary key auto_increment,
    system_name varchar(128) not null,
    language varchar(32) not null,
    timezone varchar(64) not null,
    retention_days int not null,
    max_diff_lines int not null,
    auto_comment tinyint(1) not null default 1,
    auto_retry tinyint(1) not null default 1,
    github_comment tinyint(1) not null default 1,
    high_risk_pr tinyint(1) not null default 1,
    failed_task tinyint(1) not null default 1,
    notification_email varchar(255),
    webhook_signature tinyint(1) not null default 1,
    secret_masking tinyint(1) not null default 1,
    public_repo_allowed tinyint(1) not null default 0,
    token_ttl_days int not null,
    created_at datetime not null,
    updated_at datetime not null
);

create table if not exists system_setting_log (
    id bigint primary key auto_increment,
    operator varchar(64) not null,
    action varchar(255) not null,
    status varchar(32) not null,
    created_at datetime not null,
    key idx_system_setting_log_created_at (created_at)
);

insert into system_settings_config
    (id, system_name, language, timezone, retention_days, max_diff_lines, auto_comment, auto_retry,
     github_comment, high_risk_pr, failed_task, notification_email, webhook_signature, secret_masking,
     public_repo_allowed, token_ttl_days, created_at, updated_at)
values
    (1, 'RepoGuard Agent', '中文', 'Asia/Shanghai', 90, 800, 1, 1,
     1, 1, 1, 'ops@repoguard.dev', 1, 1, 0, 30, now(), now())
on duplicate key update
    id = id;

insert into system_setting_log
    (operator, action, status, created_at)
select 'system', '初始化系统设置配置', '成功', now()
where not exists (
    select 1 from system_setting_log where action = '初始化系统设置配置'
);
