create table if not exists review_policy_promotion_evidence (
    id bigint primary key auto_increment,
    target_type varchar(16) not null,
    rule_policy_snapshot_id bigint null,
    strategy_policy_snapshot_id bigint null,
    rule_id varchar(64) null,
    source_enforcement_mode varchar(16) not null,
    target_enforcement_mode varchar(16) not null,
    quality_baseline_version varchar(64) not null,
    quality_gate_version varchar(64) not null,
    baseline_calculated_at datetime(6) not null,
    sample_cutoff_at datetime(6) not null,
    total_samples bigint unsigned not null,
    labeled_samples bigint unsigned not null,
    total_high_risk_samples bigint unsigned not null,
    labeled_high_risk_samples bigint unsigned not null,
    confirmed_valid_samples bigint unsigned not null,
    false_positive_samples bigint unsigned not null,
    anchored_samples bigint unsigned not null,
    duplicate_samples bigint unsigned not null,
    precision_rate decimal(7,2) not null,
    precision_wilson_lower_bound decimal(7,2) not null,
    false_positive_rate decimal(7,2) not null,
    anchor_rate decimal(7,2) not null,
    duplicate_rate decimal(7,2) not null,
    comment_eligible tinyint(1) not null,
    block_eligible tinyint(1) not null,
    quality_status varchar(32) not null,
    blockers varchar(2048) not null default '',
    sample_fingerprint varchar(96) not null,
    actor_user_id bigint null,
    actor_username varchar(255) null,
    trace_id varchar(128) null,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_review_policy_evidence_rule_snapshot (rule_policy_snapshot_id),
    unique key uk_review_policy_evidence_strategy_snapshot (strategy_policy_snapshot_id),
    key idx_review_policy_evidence_rule_created (rule_id, created_at, id),
    key idx_review_policy_evidence_created (created_at, id),
    constraint fk_review_policy_evidence_rule_snapshot
        foreign key (rule_policy_snapshot_id) references review_rule_policy_snapshot (id) on delete restrict,
    constraint fk_review_policy_evidence_strategy_snapshot
        foreign key (strategy_policy_snapshot_id) references review_strategy_policy_snapshot (id) on delete restrict,
    constraint chk_review_policy_evidence_target check (
        (target_type = 'RULE'
            and rule_policy_snapshot_id is not null
            and strategy_policy_snapshot_id is null
            and rule_id is not null)
        or
        (target_type = 'STRATEGY'
            and rule_policy_snapshot_id is null
            and strategy_policy_snapshot_id is not null
            and rule_id is null)
    ),
    constraint chk_review_policy_evidence_counts check (
        total_samples >= labeled_samples
        and total_samples >= total_high_risk_samples
        and total_high_risk_samples >= labeled_high_risk_samples
        and labeled_high_risk_samples = confirmed_valid_samples + false_positive_samples
        and total_high_risk_samples >= anchored_samples
        and total_high_risk_samples >= duplicate_samples
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
