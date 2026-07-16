#!/usr/bin/env bash
set -euo pipefail

backup_path="${1:-}"
max_total_tokens="${2:-15000}"
backend_image="${3:-}"
run_id="${4:-}"
run_attempt="${5:-}"

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
deploy_path="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
compose_file="$deploy_path/docker-compose.smoke.yml"
production_compose_file="$deploy_path/docker-compose.prod.yml"
production_env="$deploy_path/.env"
task_runner="$script_dir/run-real-chain-smoke.sh"
project="repoguard-smoke-${run_id}-${run_attempt}"
env_file=""
stack_started=0

cleanup() {
  exit_code=$?
  trap - EXIT
  if [ "$stack_started" -eq 1 ]; then
    if [ "$exit_code" -ne 0 ]; then
      echo "smoke_failure_logs project=$project"
      compose logs --no-color --tail 200 backend mysql rabbitmq 2>&1 \
        | sed -E 's/(password|token|api[_-]?key|secret)([=: ]+)[^ ,;]+/\1\2[REDACTED]/Ig' || true
    fi
    compose down --volumes --remove-orphans --timeout 30 >/dev/null 2>&1 || true
  fi
  if [ -n "$env_file" ]; then
    rm -f -- "$env_file"
  fi
  if docker ps -aq --filter "label=com.docker.compose.project=$project" | grep -q . \
      || docker volume ls -q --filter "label=com.docker.compose.project=$project" | grep -q . \
      || docker network ls -q --filter "label=com.docker.compose.project=$project" | grep -q .; then
    echo "Isolated Compose resources remain after cleanup project=$project" >&2
    exit 1
  fi
  echo "smoke_cleanup project=$project result=complete"
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

case "$run_id:$run_attempt" in
  *[!0-9:]*|:|*:|:* ) echo "run id and attempt must be numeric" >&2; exit 2 ;;
esac
case "$max_total_tokens" in
  ''|*[!0-9]*) echo "max_total_tokens must be a positive integer" >&2; exit 2 ;;
esac
test "$max_total_tokens" -gt 0 || { echo "max_total_tokens must be greater than zero" >&2; exit 2; }
test -n "$backend_image" || { echo "backend image is required" >&2; exit 2; }
test -f "$compose_file" || { echo "Missing smoke Compose file" >&2; exit 1; }
test -f "$production_compose_file" || { echo "Missing production Compose file" >&2; exit 1; }
test -x "$task_runner" || { echo "Missing executable real-chain task runner" >&2; exit 1; }
test -f "$production_env" || { echo "Missing production environment file" >&2; exit 1; }

for command in docker gzip sed awk grep curl python3 openssl mktemp; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing required command: $command" >&2; exit 1; }
done
docker compose version >/dev/null
docker image inspect "$backend_image" >/dev/null 2>&1 || { echo "Backend image is unavailable locally" >&2; exit 1; }

if [ -z "$backup_path" ]; then
  backup_path="$(find "$deploy_path/backups" -maxdepth 1 -type f -name '*pre-d1*.sql.gz' -print | sort | tail -n 1)"
fi
test -n "$backup_path" && test -f "$backup_path" || { echo "No pre-D1 database backup found" >&2; exit 1; }
gzip -t "$backup_path"
backup_database="$(sed -n 's/^MYSQL_DATABASE=//p' "$production_env" | tail -n 1)"
backup_database="${backup_database:-repoguard}"
case "$backup_database" in
  *[!a-zA-Z0-9_]*) echo "MYSQL_DATABASE in production environment is not a safe identifier" >&2; exit 1 ;;
esac

smoke_port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
env_file="$(mktemp "$deploy_path/.smoke-${run_id}-${run_attempt}.XXXXXX.env")"
chmod 0600 "$env_file"
cat >"$env_file" <<EOF
SMOKE_BACKEND_IMAGE=$backend_image
SMOKE_BACKEND_PORT=$smoke_port
SMOKE_MYSQL_ROOT_PASSWORD=$(openssl rand -hex 32)
SMOKE_MYSQL_DATABASE=repoguard
SMOKE_MYSQL_USER=repoguard
SMOKE_MYSQL_PASSWORD=$(openssl rand -hex 32)
SMOKE_RABBITMQ_USER=repoguard
SMOKE_RABBITMQ_PASSWORD=$(openssl rand -hex 32)
SMOKE_RABBITMQ_VHOST=/repoguard
SMOKE_AUTH_TOKEN_SECRET=$(openssl rand -hex 32)
SMOKE_ADMIN_API_KEY=$(openssl rand -hex 32)
EOF

compose() {
  docker compose --env-file "$production_env" --env-file "$env_file" -f "$compose_file" -p "$project" "$@"
}

echo "smoke_project=$project"
echo "smoke_backup=$(basename "$backup_path")"
echo "smoke_backend_image=$backend_image"
echo "smoke_port=$smoke_port"

stack_started=1
compose up -d mysql rabbitmq
for _ in $(seq 1 60); do
  mysql_container="$(compose ps -q mysql)"
  rabbitmq_container="$(compose ps -q rabbitmq)"
  mysql_health="$(docker inspect --format '{{.State.Health.Status}}' "$mysql_container" 2>/dev/null || true)"
  rabbitmq_health="$(docker inspect --format '{{.State.Health.Status}}' "$rabbitmq_container" 2>/dev/null || true)"
  if [ "$mysql_health" = "healthy" ] && [ "$rabbitmq_health" = "healthy" ]; then
    break
  fi
  sleep 5
done
test "${mysql_health:-}" = "healthy" && test "${rabbitmq_health:-}" = "healthy" || {
  echo "Isolated dependencies did not become healthy" >&2
  exit 1
}

gzip -dc "$backup_path" \
  | sed -E \
      -e '/^(CREATE DATABASE|DROP DATABASE|USE )/d' \
      -e "s/\`${backup_database}\`\./\`repoguard\`./g" \
  | docker exec -i "$mysql_container" sh -lc \
      'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' >/dev/null

compose up -d backend
for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:${smoke_port}/actuator/health" | grep -q '"status":"UP"'; then
    break
  fi
  sleep 5
done
curl -fsS "http://127.0.0.1:${smoke_port}/actuator/health" | grep -q '"status":"UP"' || {
  echo "Isolated backend did not become healthy" >&2
  exit 1
}

production_mysql_container="$(
  cd "$deploy_path"
  docker compose --env-file "$production_env" -f "$production_compose_file" ps -q mysql
)"
test -n "$production_mysql_container" || {
  echo "Production MySQL container is unavailable for the review policy overlay" >&2
  exit 1
}
production_policy_rows="$(docker exec "$production_mysql_container" sh -lc \
  'mysql -N -s -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM review_policy_config WHERE id = 1"')"
test "$production_policy_rows" = "1" || {
  echo "Expected exactly one production review policy row, found ${production_policy_rows:-0}" >&2
  exit 1
}
docker exec "$production_mysql_container" sh -lc \
  'exec mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction --skip-lock-tables --skip-add-locks --no-create-info --replace --skip-comments --compact --set-gtid-purged=OFF --where="id = 1" "$MYSQL_DATABASE" review_policy_config' \
  | docker exec -i "$mysql_container" sh -lc \
      'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' >/dev/null
echo "smoke_review_policy_source=production_encrypted_row"

admin_key="$(sed -n 's/^SMOKE_ADMIN_API_KEY=//p' "$env_file")"
REPOGUARD_ADMIN_API_KEY="$admin_key" "$task_runner" \
  "http://127.0.0.1:${smoke_port}" "$mysql_container" "$project" "$max_total_tokens"
