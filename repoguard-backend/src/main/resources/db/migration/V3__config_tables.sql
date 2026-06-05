create table if not exists integration_config (
    id bigint primary key auto_increment,
    provider varchar(32) not null,
    status varchar(32) not null,
    base_url varchar(512) not null,
    token_value text,
    default_owner varchar(128),
    default_repo varchar(128),
    last_checked_at datetime,
    last_error varchar(1024),
    created_at datetime not null,
    updated_at datetime not null,
    unique key uk_integration_config_provider (provider)
);

create table if not exists review_policy_config (
    id bigint primary key auto_increment,
    llm_enabled tinyint(1) not null default 1,
    llm_provider varchar(64) not null,
    model_name varchar(128) not null,
    base_url varchar(512),
    api_key_value text,
    timeout_seconds int not null,
    temperature decimal(4, 2) not null,
    max_tokens int not null,
    fallback_to_rules tinyint(1) not null default 1,
    worker_concurrency int not null default 1,
    created_at datetime not null,
    updated_at datetime not null
);

insert into integration_config
    (provider, status, base_url, token_value, default_owner, default_repo, last_checked_at, last_error, created_at, updated_at)
values
    ('GITHUB', 'NOT_CONFIGURED', 'https://api.github.com', null, null, null, null, null, now(), now())
on duplicate key update
    provider = provider;

insert into review_policy_config
    (id, llm_enabled, llm_provider, model_name, base_url, api_key_value, timeout_seconds, temperature, max_tokens, fallback_to_rules, worker_concurrency, created_at, updated_at)
values
    (1, 1, 'dashscope', 'qwen-plus', 'https://dashscope.aliyuncs.com/compatible-mode/v1', null, 60, 0.20, 4096, 1, 1, now(), now())
on duplicate key update
    id = id;
