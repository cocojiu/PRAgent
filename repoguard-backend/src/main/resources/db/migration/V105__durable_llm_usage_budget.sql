-- Durable accounting is independent of mutable task/attempt presentation and retention.
create table llm_usage_month (
    tenant_id bigint not null,
    usage_month date not null,
    accounted_tokens bigint not null default 0,
    accounted_cost decimal(22,8) not null default 0,
    unpriced_calls bigint not null default 0,
    legacy_unknown_attempts bigint not null default 0,
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (tenant_id, usage_month),
    constraint fk_llm_usage_month_tenant foreign key (tenant_id) references tenant(id),
    constraint chk_llm_usage_month_nonnegative check (
        accounted_tokens >= 0 and accounted_cost >= 0 and unpriced_calls >= 0 and legacy_unknown_attempts >= 0
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table llm_usage_call (
    tenant_id bigint not null,
    call_key varchar(128) not null,
    usage_month date not null,
    task_id bigint null,
    attempt_id bigint null,
    operation varchar(64) not null,
    provider varchar(64) not null,
    model_name varchar(128) not null,
    request_fingerprint char(64) not null,
    status varchar(24) not null,
    accounted_tokens bigint not null,
    accounted_cost decimal(22,8) null,
    pricing_json json null,
    usage_json json null,
    started_at datetime(6) not null,
    settled_at datetime(6) null,
    primary key (tenant_id, call_key),
    key idx_llm_usage_call_month_status (tenant_id, usage_month, status, started_at),
    constraint fk_llm_usage_call_month foreign key (tenant_id, usage_month)
        references llm_usage_month(tenant_id, usage_month),
    constraint chk_llm_usage_call_status check (status in ('RESERVED', 'SETTLED', 'UNKNOWN', 'LEGACY')),
    constraint chk_llm_usage_call_nonnegative check (accounted_tokens >= 0 and (accounted_cost is null or accounted_cost >= 0))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- Preserve every retained attempt, including previous retries. Unknown historical attempts
-- are explicit; a new finite monthly budget must not silently assume they were free.
insert into llm_usage_month (
    tenant_id, usage_month, accounted_tokens, accounted_cost, unpriced_calls, legacy_unknown_attempts
)
select tenant_id, cast(date_format(started_at, '%Y-%m-01') as date),
       sum(greatest(coalesce(total_tokens, prompt_tokens + completion_tokens, 0), 0)),
       sum(case when json_unquote(json_extract(cost_snapshot_json,'$.currency')) = 'CNY' then greatest(coalesce(estimated_cost,0),0) else 0 end),
       sum(case when (estimated_cost is null or coalesce(json_unquote(json_extract(cost_snapshot_json,'$.currency')),'') <> 'CNY')
           and (coalesce(total_tokens, prompt_tokens + completion_tokens, 0) > 0 or coalesce(estimated_cost,0) > 0) then 1 else 0 end),
       sum(case when total_tokens is null and (prompt_tokens is null or completion_tokens is null) then 1 else 0 end)
from review_execution_attempt
group by tenant_id, cast(date_format(started_at, '%Y-%m-01') as date);

insert into llm_usage_call (
    tenant_id, call_key, usage_month, task_id, attempt_id, operation, provider, model_name,
    request_fingerprint, status, accounted_tokens, accounted_cost, pricing_json, usage_json, started_at, settled_at
)
select tenant_id, concat('legacy-attempt:', id), cast(date_format(started_at, '%Y-%m-01') as date),
       task_id, id, 'legacy_attempt', 'legacy', 'legacy', input_fingerprint, 'LEGACY',
       greatest(coalesce(total_tokens, prompt_tokens + completion_tokens, 0), 0),
       case when json_unquote(json_extract(cost_snapshot_json,'$.currency')) = 'CNY' then estimated_cost else null end, cost_snapshot_json,
       json_object('promptTokens',prompt_tokens,'completionTokens',completion_tokens,'totalTokens',total_tokens,
           'legacyEstimatedCost',estimated_cost,
           'legacyUnpriced',(estimated_cost is null or coalesce(json_unquote(json_extract(cost_snapshot_json,'$.currency')),'') <> 'CNY')
               and (coalesce(total_tokens,prompt_tokens+completion_tokens,0) > 0 or coalesce(estimated_cost,0) > 0),
           'legacyUnknown',total_tokens is null and (prompt_tokens is null or completion_tokens is null)),
       started_at, finished_at
from review_execution_attempt;

-- Very old tasks without any retained attempt keep their recorded accounting too.
insert into llm_usage_month (
    tenant_id, usage_month, accounted_tokens, accounted_cost, unpriced_calls, legacy_unknown_attempts
)
select task.tenant_id, cast(date_format(coalesce(task.started_at, task.created_at), '%Y-%m-01') as date),
       sum(greatest(coalesce(task.llm_total_tokens, task.llm_prompt_tokens + task.llm_completion_tokens, 0), 0)),
       sum(case when json_unquote(json_extract(task.llm_cost_snapshot_json,'$.currency')) = 'CNY' then greatest(coalesce(task.llm_estimated_cost,0),0) else 0 end),
       sum(case when (task.llm_estimated_cost is null or coalesce(json_unquote(json_extract(task.llm_cost_snapshot_json,'$.currency')),'') <> 'CNY')
           and (coalesce(task.llm_total_tokens,task.llm_prompt_tokens+task.llm_completion_tokens,0)>0 or coalesce(task.llm_estimated_cost,0)>0) then 1 else 0 end),
       sum(case when task.llm_total_tokens is null and (task.llm_prompt_tokens is null or task.llm_completion_tokens is null) then 1 else 0 end)
from review_task task
where not exists (select 1 from review_execution_attempt attempt where attempt.tenant_id = task.tenant_id and attempt.task_id = task.id)
  and (task.llm_total_tokens is not null or task.llm_prompt_tokens is not null or task.llm_completion_tokens is not null or task.llm_estimated_cost is not null)
group by task.tenant_id, cast(date_format(coalesce(task.started_at, task.created_at), '%Y-%m-01') as date)
on duplicate key update
    accounted_tokens = accounted_tokens + values(accounted_tokens),
    accounted_cost = accounted_cost + values(accounted_cost),
    unpriced_calls = unpriced_calls + values(unpriced_calls),
    legacy_unknown_attempts = legacy_unknown_attempts + values(legacy_unknown_attempts);

insert into llm_usage_call (
    tenant_id, call_key, usage_month, task_id, attempt_id, operation, provider, model_name,
    request_fingerprint, status, accounted_tokens, accounted_cost, pricing_json, usage_json, started_at, settled_at
)
select task.tenant_id, concat('legacy-task:', task.id),
       cast(date_format(coalesce(task.started_at, task.created_at), '%Y-%m-01') as date),
       task.id, null, 'legacy_task', 'legacy', 'legacy',
       sha2(concat('legacy-task:', task.id), 256), 'LEGACY',
       greatest(coalesce(task.llm_total_tokens, task.llm_prompt_tokens + task.llm_completion_tokens, 0), 0),
       case when json_unquote(json_extract(task.llm_cost_snapshot_json,'$.currency')) = 'CNY' then task.llm_estimated_cost else null end, task.llm_cost_snapshot_json,
       json_object('promptTokens',task.llm_prompt_tokens,'completionTokens',task.llm_completion_tokens,'totalTokens',task.llm_total_tokens,
           'legacyEstimatedCost',task.llm_estimated_cost,
           'legacyUnpriced',(task.llm_estimated_cost is null or coalesce(json_unquote(json_extract(task.llm_cost_snapshot_json,'$.currency')),'') <> 'CNY')
               and (coalesce(task.llm_total_tokens,task.llm_prompt_tokens+task.llm_completion_tokens,0)>0 or coalesce(task.llm_estimated_cost,0)>0),
           'legacyUnknown',task.llm_total_tokens is null and (task.llm_prompt_tokens is null or task.llm_completion_tokens is null)),
       coalesce(task.started_at, task.created_at), task.finished_at
from review_task task
where not exists (select 1 from review_execution_attempt attempt where attempt.tenant_id = task.tenant_id and attempt.task_id = task.id)
  and (task.llm_total_tokens is not null or task.llm_prompt_tokens is not null or task.llm_completion_tokens is not null or task.llm_estimated_cost is not null);

-- Retained tasks cannot prove that previously purged or interrupted calls were free.
-- Existing tenants need explicit evidence for the cutover month's missing historical usage.
-- Sending workers remain stopped while this checkpoint is created. Future UTC months start
-- clean because every sending worker must then use the ledger; no automatic expiry of holds.
insert into llm_usage_month(tenant_id,usage_month,legacy_unknown_attempts)
select id,cast(date_format(utc_timestamp(),'%Y-%m-01') as date),1 from tenant
on duplicate key update legacy_unknown_attempts=legacy_unknown_attempts+1;

insert into llm_usage_call(tenant_id,call_key,usage_month,operation,provider,model_name,request_fingerprint,
    status,accounted_tokens,accounted_cost,pricing_json,usage_json,started_at)
select id,concat('legacy-checkpoint:',date_format(utc_timestamp(),'%Y-%m')),cast(date_format(utc_timestamp(),'%Y-%m-01') as date),
    'legacy_checkpoint','legacy','legacy',sha2(concat('cutover:',id,':',date_format(utc_timestamp(),'%Y-%m')),256),
    'LEGACY',0,0,json_object('currency','CNY'),json_object('legacyUnknown',true,'source','retained_history_incomplete'),utc_timestamp()
from tenant;

create table llm_usage_reconciliation (
    tenant_id bigint not null,
    idempotency_key varchar(64) not null,
    call_key varchar(128) not null,
    request_fingerprint char(64) not null,
    operator_name varchar(128) not null,
    evidence_reference varchar(512) not null,
    reason varchar(512) not null,
    previous_evidence_json json not null,
    accounted_tokens bigint not null,
    accounted_cost decimal(22,8) not null,
    created_at datetime(6) not null,
    primary key (tenant_id,idempotency_key),
    constraint fk_llm_usage_reconciliation_call foreign key (tenant_id,call_key) references llm_usage_call(tenant_id,call_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
