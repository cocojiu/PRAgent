-- Safe declarative rule metadata.  Expressions are validated by the application
-- and remain data-only; no arbitrary code or SQL is evaluated by the reviewer.
alter table review_rule_config
    add column detector_type varchar(16) not null default 'BUILTIN' after detector_version,
    add column matcher_expression varchar(1024) not null default '' after detector_type,
    add column exception_patterns varchar(1024) not null default '' after matcher_expression,
    add key idx_review_rule_config_tenant_detector (tenant_id, detector_type, status),
    add constraint chk_review_rule_config_detector_type
        check (detector_type in ('BUILTIN', 'REGEX', 'AST'));

alter table review_rule_policy_snapshot
    add column detector_type varchar(16) not null default 'BUILTIN' after detector_version,
    add column matcher_expression varchar(1024) not null default '' after detector_type,
    add column exception_patterns varchar(1024) not null default '' after matcher_expression,
    add constraint chk_review_rule_snapshot_detector_type
        check (detector_type in ('BUILTIN', 'REGEX', 'AST'));
