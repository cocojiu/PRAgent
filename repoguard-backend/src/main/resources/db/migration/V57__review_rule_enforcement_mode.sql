alter table review_rule_config
    add column enforcement_mode varchar(16) not null default 'COMMENT' after confidence;

update review_rule_config
set enforcement_mode = case
    when upper(severity) in ('CRITICAL', 'HIGH') then 'BLOCK'
    when upper(status) = 'DISABLED' then 'OBSERVE'
    else 'COMMENT'
end;

update review_rule_config
set status = 'DISABLED',
    enforcement_mode = 'OBSERVE'
where upper(status) = 'ENABLED'
  and id not in (
      'RG-API-001',
      'RG-AUTH-001',
      'RG-DB-002',
      'RG-DB-003',
      'RG-EXT-001',
      'RG-GEN-001',
      'RG-GH-001',
      'RG-JAVA-001',
      'RG-JAVA-002',
      'RG-JAVA-003',
      'RG-LOG-001',
      'RG-MQ-001',
      'RG-SECRET-001',
      'RG-STATE-001'
  );

alter table review_finding
    add column enforcement_mode varchar(16) not null default 'COMMENT' after is_blocking,
    add column policy_reason varchar(255) not null default '' after enforcement_mode;

update review_finding
set enforcement_mode = case
        when is_blocking = true then 'BLOCK'
        else 'COMMENT'
    end,
    policy_reason = 'legacy_finding';

alter table review_task
    add column assessment_status varchar(16) not null default 'PARTIAL' after risk_level;

alter table review_task_archive_summary
    add column assessment_status varchar(16) not null default 'PARTIAL' after risk_level;

update review_task
set assessment_status = case
        when upper(status) = 'FAILED' then 'FAILED'
        when upper(status) = 'SUPERSEDED' then 'SUPERSEDED'
        when upper(status) in ('COMPLETED', 'PENDING_HUMAN_REVIEW', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED')
             and lower(coalesce(llm_parse_status, '')) not in ('fallback', 'partial_fallback')
             and lower(coalesce(llm_status, '')) <> 'fallback'
            then 'COMPLETE'
        else 'PARTIAL'
    end,
    risk_level = case
        when upper(status) in ('FAILED', 'SUPERSEDED') then 'INFO'
        else risk_level
    end;

update review_task_archive_summary
set assessment_status = case
    when upper(status) = 'FAILED' then 'FAILED'
    when upper(status) = 'SUPERSEDED' then 'SUPERSEDED'
    when upper(status) in ('COMPLETED', 'PENDING_HUMAN_REVIEW', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED')
        then 'COMPLETE'
    else 'PARTIAL'
end;

alter table review_task
    add key idx_review_task_assessment_created_risk (assessment_status, created_at, risk_level);
