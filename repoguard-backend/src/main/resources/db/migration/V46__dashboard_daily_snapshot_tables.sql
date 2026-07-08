create table if not exists dashboard_review_daily_stat (
    stat_date date primary key,
    task_count bigint not null default 0,
    high_risk_count bigint not null default 0,
    medium_risk_count bigint not null default 0,
    low_risk_count bigint not null default 0,
    info_risk_count bigint not null default 0,
    failed_count bigint not null default 0,
    duration_seconds_sum decimal(20, 2) not null default 0,
    duration_sample_count bigint not null default 0,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    key idx_dashboard_review_daily_updated (updated_at)
);

create table if not exists dashboard_rule_daily_stat (
    stat_date date not null,
    rule_id varchar(128) not null,
    total_count bigint not null default 0,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (stat_date, rule_id),
    key idx_dashboard_rule_daily_rule_date (rule_id, stat_date)
);

create table if not exists dashboard_llm_quality_daily_stat (
    stat_date date not null,
    model_label varchar(260) not null,
    repository_label varchar(260) not null,
    task_count bigint not null default 0,
    duration_ms_sum decimal(20, 2) not null default 0,
    duration_sample_count bigint not null default 0,
    token_sum decimal(20, 2) not null default 0,
    token_sample_count bigint not null default 0,
    cost_sum decimal(20, 8) not null default 0,
    cost_sample_count bigint not null default 0,
    parse_success_count bigint not null default 0,
    fallback_count bigint not null default 0,
    partial_fallback_count bigint not null default 0,
    reviewed_feedback_count bigint not null default 0,
    valid_feedback_count bigint not null default 0,
    false_positive_feedback_count bigint not null default 0,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (stat_date, model_label, repository_label),
    key idx_dashboard_llm_daily_model_date (model_label, stat_date),
    key idx_dashboard_llm_daily_repository_date (repository_label, stat_date),
    key idx_dashboard_llm_daily_updated (updated_at)
);
