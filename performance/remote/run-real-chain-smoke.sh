#!/usr/bin/env bash
set -euo pipefail

backup_path="${1:-}"
max_total_tokens="${2:-15000}"
base_url="${3:-http://127.0.0.1}"
shift $(( $# >= 3 ? 3 : $# ))

if [ "$#" -eq 0 ]; then
  set -- \
    "11|9db67be5669fc43a1dc94fa680677c1ea26f1d6c|codex/e2e-smoke-small-20260621|small" \
    "12|c12901123ad1d21a2d5591c41bce51fc9ce5e50a|codex/e2e-smoke-medium-20260621|medium" \
    "13|3393f487dcec9cb914e5fbe9be0364dd54ea4c78|codex/e2e-smoke-risk-20260621|risk"
fi

if [ -z "$backup_path" ]; then
  backup_path="$(find /opt/repoguard/backups -maxdepth 1 -type f -name '*pre-d1*.sql.gz' -print | sort | tail -n 1)"
fi
test -n "$backup_path" && test -f "$backup_path" || {
  echo "No pre-D1 database backup found" >&2
  exit 1
}

case "$max_total_tokens" in
  ''|*[!0-9]*) echo "max_total_tokens must be a positive integer" >&2; exit 2 ;;
esac

admin_key="$(docker exec repoguard-backend printenv REPOGUARD_ADMIN_API_KEY)"
test -n "$admin_key" || {
  echo "Runtime admin API key is unavailable" >&2
  exit 1
}

database_name="$(docker exec repoguard-mysql sh -lc 'printf %s "$MYSQL_DATABASE"')"
restore_database="repoguard_smoke_source"
snapshot_suffix="smoke_$(date +%s)"
integration_snapshot="integration_config_${snapshot_suffix}"
policy_snapshot="review_policy_config_${snapshot_suffix}"
activated=0

mysql_exec() {
  docker exec -i repoguard-mysql sh -lc \
    'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<<"$1"
}

mysql_restore_exec() {
  docker exec -i repoguard-mysql sh -lc \
    'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" repoguard_smoke_source' <<<"$1"
}

cleanup() {
  exit_code=$?
  if [ "$activated" -eq 1 ]; then
    mysql_exec "
      SET FOREIGN_KEY_CHECKS=0;
      DELETE FROM integration_config;
      INSERT INTO integration_config SELECT * FROM \`${integration_snapshot}\`;
      DELETE FROM review_policy_config;
      INSERT INTO review_policy_config SELECT * FROM \`${policy_snapshot}\`;
      DROP TABLE IF EXISTS \`${integration_snapshot}\`;
      DROP TABLE IF EXISTS \`${policy_snapshot}\`;
      SET FOREIGN_KEY_CHECKS=1;
    " >/dev/null || true
  fi
  docker exec -i repoguard-mysql sh -lc \
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS repoguard_smoke_source"' \
    >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

echo "smoke_backup=$(basename "$backup_path")"
echo "smoke_task_limit=$#"
echo "smoke_token_budget=$max_total_tokens"

docker exec -i repoguard-mysql sh -lc \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
    DROP DATABASE IF EXISTS repoguard_smoke_source;
    CREATE DATABASE repoguard_smoke_source CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  "' >/dev/null

gzip -dc "$backup_path" \
  | sed -E \
      -e '/^(CREATE DATABASE|DROP DATABASE|USE )/d' \
      -e "s/\`${database_name}\`\./\`${restore_database}\`./g" \
  | docker exec -i repoguard-mysql sh -lc \
      'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" repoguard_smoke_source' >/dev/null

source_metadata="$(mysql_restore_exec "
  SELECT CONCAT(
    'github|', provider, '|', status, '|', base_url, '|', default_owner, '|', default_repo, '|', LENGTH(token_value)
  ) FROM integration_config WHERE provider = 'GITHUB';
  SELECT CONCAT(
    'llm|', llm_enabled, '|', llm_provider, '|', model_name, '|', base_url, '|', LENGTH(api_key_value)
  ) FROM review_policy_config WHERE id = 1;
")"
printf '%s\n' "$source_metadata"

github_line="$(printf '%s\n' "$source_metadata" | awk -F'|' '$1 == "github" { print; exit }')"
llm_line="$(printf '%s\n' "$source_metadata" | awk -F'|' '$1 == "llm" { print; exit }')"
test -n "$github_line" && test -n "$llm_line" || {
  echo "Real integration rows are missing from backup" >&2
  exit 1
}
printf '%s\n' "$github_line" | grep -vq '172.18.0.1:19080' || {
  echo "Backup GitHub configuration is still Mock" >&2
  exit 1
}
printf '%s\n' "$llm_line" | grep -vq '|mock|' || {
  echo "Backup LLM configuration is still Mock" >&2
  exit 1
}
github_token_length="$(printf '%s\n' "$github_line" | awk -F'|' '{ print $7 }')"
llm_key_length="$(printf '%s\n' "$llm_line" | awk -F'|' '{ print $6 }')"
test "${github_token_length:-0}" -gt 0 && test "${llm_key_length:-0}" -gt 0 || {
  echo "Real integration credentials are absent from backup" >&2
  exit 1
}

mysql_exec "
  CREATE TABLE \`${integration_snapshot}\` LIKE integration_config;
  INSERT INTO \`${integration_snapshot}\` SELECT * FROM integration_config;
  CREATE TABLE \`${policy_snapshot}\` LIKE review_policy_config;
  INSERT INTO \`${policy_snapshot}\` SELECT * FROM review_policy_config;
  SET FOREIGN_KEY_CHECKS=0;
  DELETE FROM integration_config;
  INSERT INTO integration_config SELECT * FROM \`${restore_database}\`.integration_config;
  DELETE FROM review_policy_config;
  INSERT INTO review_policy_config SELECT * FROM \`${restore_database}\`.review_policy_config;
  SET FOREIGN_KEY_CHECKS=1;
" >/dev/null
activated=1

cumulative_tokens=0
completed_tasks=0
for specification in "$@"; do
  IFS='|' read -r pr_number commit_sha branch_name sample_type <<<"$specification"
  if [ "$cumulative_tokens" -ge "$max_total_tokens" ]; then
    echo "smoke_stop=token_budget_reached cumulative_tokens=$cumulative_tokens"
    break
  fi

  payload="$(python3 - "$pr_number" "$commit_sha" "$branch_name" "$sample_type" <<'PY'
import json
import sys
pr_number, commit_sha, branch_name, sample_type = sys.argv[1:]
print(json.dumps({
    "organization": "cocojiu",
    "repository": "PRAgent",
    "prNumber": int(pr_number),
    "title": f"Real chain smoke {sample_type} PR #{pr_number}",
    "commit": commit_sha,
    "branch": branch_name,
    "source": "REAL_CHAIN_SMOKE",
}))
PY
)"
  response="$(curl -fsS \
    -H "X-RepoGuard-Admin-Key: ${admin_key}" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    --data "$payload" \
    "${base_url%/}/api/v1/reviews/manual")"
  task_id="$(printf '%s' "$response" | python3 -c \
    'import json,sys; data=json.load(sys.stdin); print(data["data"]["taskId"])')"
  existing="$(printf '%s' "$response" | python3 -c \
    'import json,sys; data=json.load(sys.stdin); print(str(data["data"].get("existing", False)).lower())')"
  echo "smoke_created type=$sample_type pr=$pr_number task_id=$task_id existing=$existing"
  test "$existing" = "false" || {
    echo "Smoke task unexpectedly reused an existing task" >&2
    exit 1
  }

  final_status=""
  for _ in $(seq 1 120); do
    final_status="$(mysql_exec "SELECT status FROM review_task WHERE id = ${task_id};")"
    case "$final_status" in
      COMPLETED|FAILED|CANCELLED|PENDING_HUMAN_REVIEW) break ;;
    esac
    sleep 5
  done
  case "$final_status" in
    COMPLETED|FAILED|CANCELLED|PENDING_HUMAN_REVIEW) ;;
    *) echo "Smoke task did not reach a terminal status task_id=$task_id status=$final_status" >&2; exit 1 ;;
  esac

  result="$(mysql_exec "
    SELECT CONCAT_WS('|',
      id,
      pr_number,
      status,
      risk_level,
      llm_status,
      COALESCE(llm_provider, ''),
      COALESCE(llm_model, ''),
      COALESCE(llm_prompt_tokens, 0),
      COALESCE(llm_completion_tokens, 0),
      COALESCE(llm_total_tokens, 0),
      COALESCE(llm_estimated_cost, 0),
      COALESCE(duration_seconds, 0),
      (SELECT COUNT(*) FROM changed_file WHERE task_id = review_task.id),
      (SELECT COUNT(*) FROM review_finding WHERE task_id = review_task.id)
    ) FROM review_task WHERE id = ${task_id};
  ")"
  echo "smoke_result=$result"
  task_tokens="$(printf '%s\n' "$result" | awk -F'|' '{ print $10 }')"
  cumulative_tokens=$((cumulative_tokens + ${task_tokens:-0}))
  completed_tasks=$((completed_tasks + 1))
  echo "smoke_progress completed_tasks=$completed_tasks cumulative_tokens=$cumulative_tokens"
done

echo "smoke_summary completed_tasks=$completed_tasks cumulative_tokens=$cumulative_tokens token_budget=$max_total_tokens"
test "$completed_tasks" -ge 3
