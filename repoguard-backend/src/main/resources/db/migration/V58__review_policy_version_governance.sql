alter table review_rule_config
    add column detector_version varchar(96) not null default 'builtin-detector-v2' after id,
    add column config_version bigint unsigned not null default 1 after detector_version,
    add column policy_version bigint unsigned not null default 1 after config_version;

update review_rule_config
set detector_version = concat(lower(id), '-detector-v2'),
    config_version = 1,
    policy_version = 1;

create table if not exists review_rule_policy_snapshot (
    id bigint primary key auto_increment,
    rule_id varchar(64) not null,
    policy_version bigint unsigned not null,
    config_version bigint unsigned not null,
    detector_version varchar(96) not null,
    rule_name varchar(128) not null,
    scope varchar(128) not null,
    applicable_languages varchar(255) not null default '',
    file_patterns varchar(512) not null default '',
    severity varchar(16) not null,
    status varchar(16) not null,
    confidence int not null,
    enforcement_mode varchar(16) not null,
    description varchar(1024) not null,
    positive_example varchar(1024) not null default '',
    false_positive_guidance varchar(1024) not null default '',
    change_type varchar(32) not null,
    source_policy_version bigint unsigned null,
    created_at datetime not null default current_timestamp,
    unique key uk_review_rule_policy_snapshot_version (rule_id, policy_version),
    key idx_review_rule_policy_snapshot_created (rule_id, created_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into review_rule_policy_snapshot (
    rule_id,
    policy_version,
    config_version,
    detector_version,
    rule_name,
    scope,
    applicable_languages,
    file_patterns,
    severity,
    status,
    confidence,
    enforcement_mode,
    description,
    positive_example,
    false_positive_guidance,
    change_type,
    source_policy_version,
    created_at
)
select
    id,
    policy_version,
    config_version,
    detector_version,
    rule_name,
    scope,
    coalesce(applicable_languages, ''),
    coalesce(file_patterns, ''),
    severity,
    status,
    confidence,
    enforcement_mode,
    description,
    coalesce(positive_example, ''),
    coalesce(false_positive_guidance, ''),
    'BASELINE',
    null,
    coalesce(updated_at, created_at, current_timestamp)
from review_rule_config;

create table if not exists review_strategy_policy_snapshot (
    id bigint primary key auto_increment,
    strategy_version bigint unsigned not null,
    prompt_version varchar(96) not null,
    context_version varchar(96) not null,
    schema_version varchar(96) not null,
    verifier_version varchar(96) not null,
    aggregation_version varchar(96) not null,
    enforcement_mode varchar(16) not null,
    replay_verified tinyint(1) not null default 0,
    active tinyint(1) not null default 0,
    active_guard tinyint generated always as (
        case when active = 1 then 1 else null end
    ) stored,
    change_type varchar(32) not null,
    source_snapshot_id bigint null,
    created_at datetime not null default current_timestamp,
    key idx_review_strategy_policy_active (active, id),
    key idx_review_strategy_policy_version (strategy_version, id),
    unique key uk_review_strategy_policy_single_active (active_guard),
    constraint fk_review_strategy_policy_source
        foreign key (source_snapshot_id) references review_strategy_policy_snapshot (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into review_strategy_policy_snapshot (
    strategy_version,
    prompt_version,
    context_version,
    schema_version,
    verifier_version,
    aggregation_version,
    enforcement_mode,
    replay_verified,
    active,
    change_type,
    source_snapshot_id
) values (
    1,
    'review-prompt-v2',
    'review-context-v2',
    'review-schema-v2',
    'high-risk-verifier-v1',
    'server-risk-v2',
    'OBSERVE',
    1,
    1,
    'BASELINE',
    null
);

alter table review_finding
    add column issue_type varchar(128) not null default 'GENERAL' after policy_reason,
    add column preconditions text null after issue_type,
    add column related_files text null after preconditions,
    add column blocking_candidate tinyint(1) not null default 0 after related_files,
    add column verification_status varchar(32) not null default 'NOT_REQUIRED' after blocking_candidate,
    add column detector_version varchar(96) not null default 'legacy-detector-v1' after verification_status,
    add column rule_config_version bigint unsigned not null default 1 after detector_version,
    add column prompt_version varchar(96) not null default 'not-applicable' after rule_config_version,
    add column context_version varchar(96) not null default 'not-applicable' after prompt_version,
    add column schema_version varchar(96) not null default 'not-applicable' after context_version,
    add column verifier_version varchar(96) not null default 'not-applicable' after schema_version,
    add column aggregation_version varchar(96) not null default 'server-risk-v2' after verifier_version,
    add column policy_version bigint unsigned not null default 1 after aggregation_version,
    add column llm_provider varchar(64) null after policy_version,
    add column llm_model varchar(128) null after llm_provider,
    add column original_severity varchar(16) not null default 'INFO' after llm_model,
    add column original_confidence varchar(16) not null default 'LOW' after original_severity,
    add column original_is_blocking tinyint(1) not null default 0 after original_confidence,
    add column downgrade_reason varchar(512) not null default '' after original_is_blocking,
    add column block_reason varchar(512) not null default '' after downgrade_reason,
    add column anchor_type varchar(32) not null default 'NONE' after block_reason;

update review_finding finding
left join review_task task on task.id = finding.task_id
set finding.issue_type = coalesce(nullif(trim(finding.rule_id), ''), 'GENERAL'),
    finding.detector_version = case
        when upper(coalesce(finding.source, '')) like '%RULE%'
            then concat(lower(coalesce(nullif(trim(finding.rule_id), ''), 'legacy')), '-detector-v2')
        else 'llm-review-v2'
    end,
    finding.rule_config_version = 1,
    finding.prompt_version = case
        when upper(coalesce(finding.source, '')) like '%LLM%' then 'review-prompt-v2'
        else 'not-applicable'
    end,
    finding.context_version = case
        when upper(coalesce(finding.source, '')) like '%LLM%' then 'review-context-v2'
        else 'not-applicable'
    end,
    finding.schema_version = case
        when upper(coalesce(finding.source, '')) like '%LLM%' then 'review-schema-v2'
        else 'not-applicable'
    end,
    finding.verifier_version = case
        when upper(coalesce(finding.source, '')) like '%LLM%' then 'high-risk-verifier-v1'
        else 'not-applicable'
    end,
    finding.aggregation_version = 'server-risk-v2',
    finding.policy_version = 1,
    finding.llm_provider = task.llm_provider,
    finding.llm_model = task.llm_model,
    finding.original_severity = upper(coalesce(nullif(trim(finding.severity), ''), 'INFO')),
    finding.original_confidence = upper(coalesce(nullif(trim(finding.confidence), ''), 'LOW')),
    finding.original_is_blocking = finding.is_blocking,
    finding.downgrade_reason = case
        when lower(coalesce(finding.policy_reason, '')) like '%downgrade%'
          or lower(coalesce(finding.policy_reason, '')) like '%rejected%'
          or lower(coalesce(finding.policy_reason, '')) like '%below%'
            then finding.policy_reason
        else ''
    end,
    finding.block_reason = case when finding.is_blocking = 1 then finding.policy_reason else '' end,
    finding.anchor_type = case
        when finding.line_number is not null and finding.line_number > 0 then 'ADDED_LINE'
        else 'NONE'
    end;

alter table review_finding
    add key idx_review_finding_rule_version_feedback (
        category,
        rule_id,
        detector_version,
        rule_config_version,
        feedback_status_norm
    ),
    add key idx_review_finding_strategy_version (
        category,
        prompt_version,
        context_version,
        schema_version,
        verifier_version,
        aggregation_version,
        feedback_status_norm
    );
