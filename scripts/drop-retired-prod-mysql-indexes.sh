#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.prod}"
CONFIRMATION="${CONFIRM_DROP_LEGACY_DASHBOARD_INDEXES:-}"
MIN_OBSERVATION_SECONDS="${MIN_INDEX_OBSERVATION_SECONDS:-604800}"

[[ "${CONFIRMATION}" == "drop-after-observation" ]] || {
  echo "Set CONFIRM_DROP_LEGACY_DASHBOARD_INDEXES=drop-after-observation." >&2
  exit 64
}
[[ "${MIN_OBSERVATION_SECONDS}" =~ ^[0-9]+$ && "${MIN_OBSERVATION_SECONDS}" -ge 86400 ]] || {
  echo "MIN_INDEX_OBSERVATION_SECONDS must be numeric and at least 86400." >&2
  exit 64
}

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
mysql_sql() {
  "${compose[@]}" exec -T mysql sh -ec '
    password="$(cat /run/secrets/mysql.root-password)"
    MYSQL_PWD="$password" exec mysql --protocol=socket -uroot --batch --skip-column-names "$MYSQL_DATABASE" --execute="$1"
  ' sh "$1"
}

uptime="$(mysql_sql "select variable_value from performance_schema.global_status where variable_name='Uptime';")"
[[ "${uptime}" =~ ^[0-9]+$ && "${uptime}" -ge "${MIN_OBSERVATION_SECONDS}" ]] || {
  echo "MySQL performance_schema observation window is shorter than required: ${uptime:-unknown}s." >&2
  exit 1
}

read -r index_count invisible_count replacement_count total_reads <<<"$(mysql_sql "
select
  count(distinct legacy.index_name),
  count(distinct case when legacy.is_visible = 'NO' then legacy.index_name end),
  (
    select count(distinct index_name)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'review_task'
      and is_visible = 'YES'
      and index_name in (
        'idx_review_task_dashboard_created_risk_norm',
        'idx_review_task_dashboard_created_llm_model_norm',
        'idx_review_task_dashboard_created_llm_repo_norm'
      )
  ),
  coalesce(sum(usage.count_read), 0)
from (
  select table_name, index_name, is_visible
  from information_schema.statistics
  where table_schema = database()
    and table_name = 'review_task'
    and index_name in (
      'idx_review_task_dashboard_created_risk',
      'idx_review_task_dashboard_created_llm_model',
      'idx_review_task_dashboard_created_llm_repo'
    )
  group by table_name, index_name, is_visible
) legacy
left join performance_schema.table_io_waits_summary_by_index_usage usage
  on usage.object_schema = database()
 and usage.object_name = legacy.table_name
 and usage.index_name = legacy.index_name
;
")"

[[ "${index_count}" == "3" && "${invisible_count}" == "3" && "${replacement_count}" == "3" ]] || {
  echo "Legacy/replacement index inventory did not satisfy the guarded drop contract." >&2
  exit 1
}
[[ "${total_reads}" == "0" ]] || {
  echo "Legacy indexes recorded ${total_reads} reads during the observation window." >&2
  exit 1
}

mysql_sql "
alter table review_task
  drop index idx_review_task_dashboard_created_risk,
  drop index idx_review_task_dashboard_created_llm_model,
  drop index idx_review_task_dashboard_created_llm_repo;
analyze table review_task;
" >/dev/null

remaining="$(mysql_sql "
select count(distinct index_name)
from information_schema.statistics
where table_schema = database()
  and table_name = 'review_task'
  and index_name in (
    'idx_review_task_dashboard_created_risk',
    'idx_review_task_dashboard_created_llm_model',
    'idx_review_task_dashboard_created_llm_repo'
  );
")"
[[ "${remaining}" == "0" ]] || { echo "One or more retired indexes remain." >&2; exit 1; }

echo "INDEX_DROP_APPLIED=true"
echo "OBSERVATION_SECONDS=${uptime}"
echo "DROPPED_INDEX_COUNT=3"
