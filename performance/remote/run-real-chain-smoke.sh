#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-}"
mysql_container="${2:-}"
compose_project="${3:-}"
max_total_tokens="${4:-15000}"
shift $(( $# >= 4 ? 4 : $# ))

test -n "$base_url" || { echo "backend URL is required" >&2; exit 2; }
test -n "$mysql_container" || { echo "MySQL container ID is required" >&2; exit 2; }
test -n "$compose_project" || { echo "Compose project is required" >&2; exit 2; }
test -n "${REPOGUARD_ADMIN_API_KEY:-}" || { echo "isolated admin API key is required" >&2; exit 2; }

case "$max_total_tokens" in
  ''|*[!0-9]*) echo "max_total_tokens must be a positive integer" >&2; exit 2 ;;
esac
test "$max_total_tokens" -gt 0 || { echo "max_total_tokens must be greater than zero" >&2; exit 2; }

actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$mysql_container")"
test "$actual_project" = "$compose_project" || {
  echo "MySQL container does not belong to the requested Compose project" >&2
  exit 1
}

if [ "$#" -eq 0 ]; then
  set -- \
    "11|9db67be5669fc43a1dc94fa680677c1ea26f1d6c|codex/e2e-smoke-small-20260621|small" \
    "12|c12901123ad1d21a2d5591c41bce51fc9ce5e50a|codex/e2e-smoke-medium-20260621|medium" \
    "13|3393f487dcec9cb914e5fbe9be0364dd54ea4c78|codex/e2e-smoke-risk-20260621|risk"
fi

mysql_exec() {
  docker exec -i "$mysql_container" sh -lc \
    'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<<"$1"
}

integration_metadata="$(mysql_exec "
  SELECT CONCAT('github|', provider, '|', status, '|', base_url, '|', default_owner, '|', default_repo, '|', LENGTH(token_value))
    FROM integration_config WHERE provider = 'GITHUB';
  SELECT CONCAT('llm|', llm_enabled, '|', llm_provider, '|', model_name, '|', base_url, '|', LENGTH(api_key_value))
    FROM review_policy_config WHERE id = 1;
")"
printf '%s\n' "$integration_metadata"

github_line="$(printf '%s\n' "$integration_metadata" | awk -F'|' '$1 == "github" { print; exit }')"
llm_line="$(printf '%s\n' "$integration_metadata" | awk -F'|' '$1 == "llm" { print; exit }')"
test -n "$github_line" && test -n "$llm_line" || { echo "Real integration rows are missing from backup" >&2; exit 1; }
printf '%s\n' "$github_line" | grep -vq '172.18.0.1:19080' || { echo "Backup GitHub configuration is still Mock" >&2; exit 1; }
printf '%s\n' "$llm_line" | grep -vq '|mock|' || { echo "Backup LLM configuration is still Mock" >&2; exit 1; }
github_token_length="$(printf '%s\n' "$github_line" | awk -F'|' '{ print $7 }')"
llm_key_length="$(printf '%s\n' "$llm_line" | awk -F'|' '{ print $6 }')"
test "${github_token_length:-0}" -gt 0 && test "${llm_key_length:-0}" -gt 0 || {
  echo "Real integration credentials are absent from backup" >&2
  exit 1
}

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
    -H "X-RepoGuard-Admin-Key: ${REPOGUARD_ADMIN_API_KEY}" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    --data "$payload" \
    "${base_url%/}/api/v1/reviews/manual")"
  task_id="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["taskId"])')"
  existing="$(printf '%s' "$response" | python3 -c 'import json,sys; print(str(json.load(sys.stdin)["data"].get("existing", False)).lower())')"
  echo "smoke_created type=$sample_type pr=$pr_number task_id=$task_id existing=$existing"
  test "$existing" = "false" || { echo "Smoke task unexpectedly reused an existing task" >&2; exit 1; }

  final_status=""
  for _ in $(seq 1 120); do
    final_status="$(mysql_exec "SELECT status FROM review_task WHERE id = ${task_id};")"
    case "$final_status" in
      COMPLETED|FAILED|CANCELLED|PENDING_HUMAN_REVIEW) break ;;
    esac
    sleep 5
  done
  case "$final_status" in
    COMPLETED|PENDING_HUMAN_REVIEW) ;;
    *) echo "Smoke task did not reach an accepted status task_id=$task_id status=$final_status" >&2; exit 1 ;;
  esac

  result="$(mysql_exec "
    SELECT CONCAT_WS('|', id, pr_number, status, risk_level, llm_status,
      COALESCE(llm_provider, ''), COALESCE(llm_model, ''),
      COALESCE(llm_prompt_tokens, 0), COALESCE(llm_completion_tokens, 0),
      COALESCE(llm_total_tokens, 0), COALESCE(llm_estimated_cost, 0),
      COALESCE(duration_seconds, 0),
      (SELECT COUNT(*) FROM changed_file WHERE task_id = review_task.id),
      (SELECT COUNT(*) FROM review_finding WHERE task_id = review_task.id))
    FROM review_task WHERE id = ${task_id};
  ")"
  echo "smoke_result=$result"
  IFS='|' read -r _ _ _ _ llm_status llm_provider llm_model _ _ task_tokens _ _ changed_files findings <<<"$result"
  test "$llm_status" = "COMPLETED" || { echo "Real LLM review did not complete task_id=$task_id status=$llm_status" >&2; exit 1; }
  test -n "$llm_provider" && test -n "$llm_model" || { echo "LLM provider/model missing task_id=$task_id" >&2; exit 1; }
  test "${task_tokens:-0}" -gt 0 || { echo "LLM token usage missing task_id=$task_id" >&2; exit 1; }
  test "${changed_files:-0}" -gt 0 || { echo "Changed files missing task_id=$task_id" >&2; exit 1; }
  if [ "$sample_type" = "risk" ]; then
    test "${findings:-0}" -gt 0 || { echo "Risk sample produced no findings task_id=$task_id" >&2; exit 1; }
  fi

  cumulative_tokens=$((cumulative_tokens + task_tokens))
  test "$cumulative_tokens" -le "$max_total_tokens" || {
    echo "Smoke token budget exceeded cumulative_tokens=$cumulative_tokens token_budget=$max_total_tokens" >&2
    exit 1
  }
  completed_tasks=$((completed_tasks + 1))
  echo "smoke_progress completed_tasks=$completed_tasks cumulative_tokens=$cumulative_tokens findings=$findings"
done

echo "smoke_summary completed_tasks=$completed_tasks cumulative_tokens=$cumulative_tokens token_budget=$max_total_tokens"
test "$completed_tasks" -ge 3
