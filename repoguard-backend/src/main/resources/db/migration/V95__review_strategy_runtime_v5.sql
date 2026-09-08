-- Activate the current runtime protocol as a fresh OBSERVE snapshot for every
-- tenant whose active snapshot still references an older prompt or verifier.
-- Preserve the previous active row as immutable rollback history.
create temporary table tmp_review_strategy_runtime_v5 as
select
    id as source_snapshot_id,
    tenant_id,
    strategy_version
from review_strategy_policy_snapshot
where active = 1
  and (
      prompt_version <> 'review-prompt-v5'
      or context_version <> 'review-context-v2'
      or schema_version <> 'review-schema-v2'
      or verifier_version <> 'finding-verifier-v2'
      or aggregation_version <> 'server-risk-v2'
  );

update review_strategy_policy_snapshot snapshot
join tmp_review_strategy_runtime_v5 pending
  on pending.source_snapshot_id = snapshot.id
 and pending.tenant_id = snapshot.tenant_id
set snapshot.active = 0;

insert into review_strategy_policy_snapshot (
    tenant_id,
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
)
select
    tenant_id,
    strategy_version,
    'review-prompt-v5',
    'review-context-v2',
    'review-schema-v2',
    'finding-verifier-v2',
    'server-risk-v2',
    'OBSERVE',
    1,
    1,
    'RUNTIME_VERSION_UPGRADE',
    source_snapshot_id
from tmp_review_strategy_runtime_v5;

drop temporary table tmp_review_strategy_runtime_v5;
