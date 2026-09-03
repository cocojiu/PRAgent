-- Immutable aggregate-only LLM evaluation reports; raw PR/source/provider payloads are excluded.
create table llm_evaluation_report (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    report_key char(64) not null,
    status varchar(16) not null default 'COMPLETED',
    dataset_id varchar(128) not null,
    dataset_version varchar(64) not null,
    dataset_kind varchar(32) not null,
    source_repository_count int not null,
    sample_count int not null,
    fixed_regression_samples int not null,
    rolling_observation_samples int not null,
    authorized boolean not null,
    anonymized boolean not null,
    human_reviewed boolean not null,
    manifest_fingerprint char(64) not null,
    observed_sample_fingerprint char(64) not null,
    provider varchar(64) not null,
    model varchar(128) not null,
    prompt_version varchar(96) not null,
    context_version varchar(96) not null,
    schema_version varchar(96) not null,
    chunk_policy_version varchar(128) not null,
    temperature decimal(8, 4) not null,
    rule_version varchar(96) not null,
    code_revision varchar(128) not null,
    expected_findings int not null,
    predicted_findings int not null,
    true_positives int not null,
    false_positives int not null,
    false_negatives int not null,
    precision_rate decimal(8, 4) not null,
    recall_rate decimal(8, 4) not null,
    precision_wilson_lower_bound decimal(8, 4) not null,
    anchor_rate decimal(8, 4) not null,
    duplicate_rate decimal(8, 4) not null,
    parse_failure_rate decimal(8, 4) not null,
    severity_confusion_json longtext not null,
    total_latency_ms bigint not null,
    total_tokens bigint not null,
    total_cost decimal(20, 8) not null,
    blockers_json longtext not null,
    eligible boolean not null,
    metrics_json longtext not null,
    created_by varchar(128) not null,
    created_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_evaluation_report_tenant_id (tenant_id, id),
    unique key uk_llm_evaluation_report_tenant_key (tenant_id, report_key),
    key idx_llm_evaluation_report_tenant_dataset (tenant_id, dataset_id, dataset_version, created_at),
    key idx_llm_evaluation_report_tenant_status (tenant_id, status, created_at),
    constraint fk_llm_evaluation_report_tenant foreign key (tenant_id) references tenant(id),
    constraint chk_llm_evaluation_report_status check (status in ('COMPLETED', 'FAILED', 'CANCELLED')),
    constraint chk_llm_evaluation_report_kind check (dataset_kind in ('REAL_PR', 'OFFLINE_SYNTHETIC')),
    constraint chk_llm_evaluation_report_counts check (
        source_repository_count >= 0 and sample_count between 0 and 100
        and fixed_regression_samples >= 0 and rolling_observation_samples >= 0
        and fixed_regression_samples + rolling_observation_samples = sample_count
        and expected_findings >= 0 and predicted_findings >= 0
        and true_positives >= 0 and false_positives >= 0 and false_negatives >= 0
        and total_latency_ms >= 0 and total_tokens >= 0
    ),
    constraint chk_llm_evaluation_report_rates check (
        precision_rate between 0 and 1 and recall_rate between 0 and 1
        and precision_wilson_lower_bound between 0 and 1 and anchor_rate between 0 and 1
        and duplicate_rate between 0 and 1 and parse_failure_rate between 0 and 1
        and temperature between 0 and 2 and total_cost >= 0
    ),
    constraint chk_llm_evaluation_report_fingerprints check (
        report_key regexp '^[0-9a-fA-F]{64}$'
        and manifest_fingerprint regexp '^[0-9a-fA-F]{64}$'
        and observed_sample_fingerprint regexp '^[0-9a-fA-F]{64}$'
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table llm_model_release
    add column evaluation_report_id bigint null after dataset_fingerprint,
    add key idx_llm_model_release_tenant_report (tenant_id, evaluation_report_id),
    add constraint fk_llm_model_release_evaluation_report
        foreign key (tenant_id, evaluation_report_id)
        references llm_evaluation_report(tenant_id, id);
