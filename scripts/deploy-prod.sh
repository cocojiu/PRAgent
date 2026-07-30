#!/usr/bin/env sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
COMPOSE_ADDITIONAL_FILES="${COMPOSE_ADDITIONAL_FILES:-}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1/actuator/health}"
BACKEND_SERVICE="${BACKEND_SERVICE:-backend}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
LEGACY_COMPOSE_FILE="${LEGACY_COMPOSE_FILE:-}"
LEGACY_ENV_FILE="${LEGACY_ENV_FILE:-}"
DEPLOY_LOCK_FILE="${DEPLOY_LOCK_FILE:-.deploy.lock}"
DEPLOY_ASSET_BACKUP_DIR="${DEPLOY_ASSET_BACKUP_DIR:-}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-.deploy-state}"
PREFLIGHT_ONLY="${PREFLIGHT_ONLY:-false}"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Missing compose file: $COMPOSE_FILE" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  echo "Create it from .env.prod.example and fill production secrets." >&2
  exit 1
fi

if [ -z "${BACKEND_IMAGE:-}" ]; then
  echo "Missing BACKEND_IMAGE environment variable." >&2
  exit 1
fi

if [ -z "${FRONTEND_IMAGE:-}" ]; then
  echo "Missing FRONTEND_IMAGE environment variable." >&2
  exit 1
fi

case "$PREFLIGHT_ONLY" in
  true|false) ;;
  *)
    echo "PREFLIGHT_ONLY must be true or false." >&2
    exit 1
    ;;
esac

read_env_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

validate_production_data_routing() {
  compose_environment="$(compose config --environment)"
  routing_keys="
APP_MYSQL_DATABASE
APP_RABBITMQ_VIRTUAL_HOST
RABBITMQ_DEFAULT_VHOST
REPOGUARD_REVIEW_EXCHANGE
REPOGUARD_REVIEW_QUEUE
REPOGUARD_REVIEW_ROUTING_KEY
REPOGUARD_REVIEW_DLX
REPOGUARD_REVIEW_DLQ
REPOGUARD_REVIEW_DLQ_ROUTING_KEY
REPOGUARD_NOTIFICATION_EXCHANGE
REPOGUARD_NOTIFICATION_QUEUE
REPOGUARD_NOTIFICATION_ROUTING_KEY
REPOGUARD_NOTIFICATION_DLX
REPOGUARD_NOTIFICATION_DLQ
REPOGUARD_NOTIFICATION_DLQ_ROUTING_KEY
"

  for key in $routing_keys; do
    value="$(printf '%s\n' "$compose_environment" | sed -n "s/^${key}=//p" | tail -n 1)"
    case "$value" in
      *_test|*-test|*.test.*)
        echo "Refusing production deployment: $key resolves to test routing." >&2
        return 1
        ;;
    esac
  done
}

if [ -z "$LEGACY_COMPOSE_FILE" ]; then
  LEGACY_COMPOSE_FILE="$(read_env_value LEGACY_COMPOSE_FILE)"
fi

if [ -z "$LEGACY_ENV_FILE" ]; then
  LEGACY_ENV_FILE="$(read_env_value LEGACY_ENV_FILE)"
fi

if [ -z "$COMPOSE_PROJECT_NAME" ]; then
  COMPOSE_PROJECT_NAME="$(read_env_value COMPOSE_PROJECT_NAME)"
fi

if [ -z "$COMPOSE_ADDITIONAL_FILES" ]; then
  COMPOSE_ADDITIONAL_FILES="$(read_env_value COMPOSE_ADDITIONAL_FILES)"
fi

if [ -z "${COMPOSE_PROFILES:-}" ]; then
  COMPOSE_PROFILES="$(read_env_value COMPOSE_PROFILES)"
fi

export COMPOSE_PROJECT_NAME
export COMPOSE_PROFILES

compose() {
  compose_file_list="$COMPOSE_FILE"
  for additional_file in $COMPOSE_ADDITIONAL_FILES; do
    compose_file_list="${compose_file_list}:${additional_file}"
  done
  COMPOSE_FILE="$compose_file_list" COMPOSE_PATH_SEPARATOR=: \
    docker compose --env-file "$ENV_FILE" "$@"
}

validate_secret_files() {
  for required_command in od stat tail; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
      echo "Missing required secret preflight command: $required_command" >&2
      echo "No running service has been changed." >&2
      return 1
    fi
  done

  compose_directory="$(dirname "$COMPOSE_FILE")"
  compose_environment="$(compose config --environment)"
  secret_file_keys="
MYSQL_ROOT_PASSWORD_FILE
MYSQL_PASSWORD_FILE
REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE
REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE
REPOGUARD_AUTH_TOKEN_SECRET_FILE
REPOGUARD_ADMIN_API_KEY_FILE
REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE
"

  for key in $secret_file_keys; do
    configured_path="$(printf '%s\n' "$compose_environment" \
      | sed -n "s/^${key}=//p" | tail -n 1)"
    if [ -z "$configured_path" ]; then
      echo "Missing required secret file setting: $key" >&2
      echo "No running service has been changed." >&2
      return 1
    fi

    case "$configured_path" in
      /*) secret_path="$configured_path" ;;
      *) secret_path="${compose_directory}/${configured_path}" ;;
    esac

    if [ -L "$secret_path" ] || [ ! -f "$secret_path" ] \
      || [ ! -r "$secret_path" ] || [ ! -s "$secret_path" ]; then
      echo "$key must reference a readable, non-empty regular file, not a symlink: $secret_path" >&2
      echo "No running service has been changed." >&2
      return 1
    fi

    file_mode="$(stat -c '%a' "$secret_path")"
    case "$file_mode" in
      400|600) ;;
      *)
        echo "$key must use mode 0400 or 0600; found $file_mode on $secret_path" >&2
        echo "No running service has been changed." >&2
        return 1
        ;;
    esac

    directory_mode="$(stat -c '%a' "$(dirname "$secret_path")")"
    case "$directory_mode" in
      500|700) ;;
      *)
        echo "Secret directory must use mode 0500 or 0700; found $directory_mode for $(dirname "$secret_path")" >&2
        echo "No running service has been changed." >&2
        return 1
        ;;
    esac

    last_byte="$(tail -c 1 "$secret_path" | od -An -t u1 | tr -d ' \n')"
    case "$last_byte" in
      10|13)
        echo "$key contains a trailing newline; rewrite it with printf '%s': $secret_path" >&2
        echo "No running service has been changed." >&2
        return 1
        ;;
    esac
  done
}

validate_edge_observability_isolation() {
  compose_directory="$(dirname "$COMPOSE_FILE")"
  edge_config="${compose_directory}/Caddyfile"
  if grep -Eiq 'grafana|loki|alloy|repoguard_observability' "$edge_config"; then
    echo "Production edge configuration must not route to observability services: $edge_config" >&2
    echo "Use the loopback Grafana port through an SSH tunnel." >&2
    echo "No running service has been changed." >&2
    return 1
  fi
}

validate_required_bind_sources() {
  compose_directory="$(dirname "$COMPOSE_FILE")"
  required_bind_sources="
Caddyfile
config/rabbitmq/rabbitmq.conf
"

  for relative_path in $required_bind_sources; do
    source_path="${compose_directory}/${relative_path}"
    if [ ! -f "$source_path" ] || [ ! -r "$source_path" ] || [ ! -s "$source_path" ]; then
      echo "Missing, unreadable, or empty required bind source: $source_path" >&2
      echo "No running service has been changed." >&2
      return 1
    fi
  done

  metrics_bridge_enabled=false
  for additional_file in $COMPOSE_ADDITIONAL_FILES; do
    if [ ! -f "$additional_file" ] || [ ! -r "$additional_file" ] || [ ! -s "$additional_file" ]; then
      echo "Missing, unreadable, or empty additional Compose file: $additional_file" >&2
      echo "No running service has been changed." >&2
      return 1
    fi
    if [ "$(basename "$additional_file")" = "docker-compose.metrics-bridge.yml" ]; then
      metrics_bridge_enabled=true
    fi
  done

  if [ "$metrics_bridge_enabled" = "true" ] \
    && ! docker network inspect repoguard_observability >/dev/null 2>&1; then
    echo "Metrics bridge requires the repoguard_observability network." >&2
    echo "Start the observability stack before deploying the application." >&2
    echo "No running service has been changed." >&2
    return 1
  fi

  if ! command -v sha256sum >/dev/null 2>&1; then
    echo "Missing required deployment command: sha256sum" >&2
    echo "No running service has been changed." >&2
    return 1
  fi

  # Resolve the complete model while deployment is still side-effect free.
  compose config >/dev/null
}

validate_review_timeout_layering() {
  compose_environment="$(compose config --environment)"
  pipeline_budget_ms="$(printf '%s\n' "$compose_environment" \
    | sed -n 's/^REPOGUARD_REVIEW_PIPELINE_BUDGET_MS=//p' | tail -n 1)"
  recovery_timeout_ms="$(printf '%s\n' "$compose_environment" \
    | sed -n 's/^REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS=//p' | tail -n 1)"
  pipeline_budget_ms="${pipeline_budget_ms:-600000}"
  recovery_timeout_ms="${recovery_timeout_ms:-1800000}"

  rabbitmq_config="$(dirname "$COMPOSE_FILE")/config/rabbitmq/rabbitmq.conf"
  consumer_timeout_ms="$(sed -n \
    's/^[[:space:]]*consumer_timeout[[:space:]]*=[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' \
    "$rabbitmq_config" | tail -n 1)"

  for numeric_value in "$pipeline_budget_ms" "$consumer_timeout_ms" "$recovery_timeout_ms"; do
    case "$numeric_value" in
      *[!0-9]*|"")
        echo "Review timeout values must be positive integer milliseconds." >&2
        return 1
        ;;
    esac
    if [ "$numeric_value" -le 0 ]; then
      echo "Review timeout values must be positive integer milliseconds." >&2
      return 1
    fi
  done

  if [ "$pipeline_budget_ms" -ge "$consumer_timeout_ms" ] \
    || [ "$consumer_timeout_ms" -ge "$recovery_timeout_ms" ]; then
    echo "Invalid review timeout layering." >&2
    echo "  pipeline budget:             $pipeline_budget_ms ms" >&2
    echo "  RabbitMQ consumer_timeout:   $consumer_timeout_ms ms" >&2
    echo "  recovery staleness timeout:  $recovery_timeout_ms ms" >&2
    echo "Required: pipeline budget < consumer_timeout < recovery timeout." >&2
    return 1
  fi
}

rabbitmq_config_digest() {
  current_config="$(dirname "$COMPOSE_FILE")/config/rabbitmq/rabbitmq.conf"
  sha256sum "$current_config" | awk '{print $1}'
}

rabbitmq_config_requires_restart() {
  current_digest="$(rabbitmq_config_digest)"
  applied_digest=""
  if [ -f "$DEPLOY_STATE_DIR/rabbitmq.conf.sha256" ]; then
    applied_digest="$(sed -n '1p' "$DEPLOY_STATE_DIR/rabbitmq.conf.sha256")"
  fi
  if [ -n "$applied_digest" ] && [ "$current_digest" = "$applied_digest" ]; then
    return 1
  fi
  # Missing state also forces a restart. This covers first deployment, manual
  # deployment, and a prior upload that failed before the broker was recreated.
  return 0
}

record_rabbitmq_config_digest() {
  mkdir -p "$DEPLOY_STATE_DIR"
  digest_file="$DEPLOY_STATE_DIR/rabbitmq.conf.sha256"
  digest_tmp="${digest_file}.tmp.$$"
  rabbitmq_config_digest > "$digest_tmp"
  mv "$digest_tmp" "$digest_file"
}

invalidate_rabbitmq_config_digest() {
  rm -f "$DEPLOY_STATE_DIR/rabbitmq.conf.sha256"
}

print_backend_logs() {
  echo "Recent backend logs:" >&2
  compose logs --tail=120 backend >&2 || true
}

wait_service_health() {
  service="$1"
  attempts="${2:-30}"
  i=1
  while [ "$i" -le "$attempts" ]; do
    container_id="$(compose ps -q "$service" 2>/dev/null || true)"
    if [ -n "$container_id" ] && [ "$(docker inspect "$container_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)" = "healthy" ]; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "Service health check failed after $attempts attempts: $service" >&2
  compose ps "$service" >&2 || true
  return 1
}

wait_backend_health() {
  attempts="${1:-30}"
  i=1
  while [ "$i" -le "$attempts" ]; do
    container_id="$(compose ps -q "$BACKEND_SERVICE" 2>/dev/null || true)"
    if [ -n "$container_id" ] && [ "$(docker inspect "$container_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)" = "healthy" ]; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "Backend container health check failed after $attempts attempts." >&2
  print_backend_logs
  return 1
}

wait_http_health() {
  attempts="${1:-30}"
  i=1
  while [ "$i" -le "$attempts" ]; do
    if command -v curl >/dev/null 2>&1; then
      if curl -fsS "$HEALTH_URL" >/dev/null; then
        return 0
      fi
    elif wget -q -O - "$HEALTH_URL" >/dev/null; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "HTTP health check failed after $attempts attempts: $HEALTH_URL" >&2
  print_backend_logs
  return 1
}

assert_service_image() {
  service="$1"
  expected_image="$2"
  container_id="$(compose ps -q "$service")"
  if [ -z "$container_id" ]; then
    echo "Missing running container for service: $service" >&2
    compose ps >&2 || true
    return 1
  fi

  actual_image="$(docker inspect "$container_id" --format '{{.Config.Image}}')"
  if [ "$actual_image" != "$expected_image" ]; then
    echo "Image mismatch for $service" >&2
    echo "  expected: $expected_image" >&2
    echo "  actual:   $actual_image" >&2
    compose ps >&2 || true
    return 1
  fi
}

has_compose_service() {
  target_service="$1"
  compose config --services | grep -Fx "$target_service" >/dev/null 2>&1
}

print_service_release_identity() {
  service="$1"
  container_id="$(compose ps -q "$service")"
  image_reference="$(docker inspect "$container_id" --format '{{.Config.Image}}')"
  image_id="$(docker inspect "$container_id" --format '{{.Image}}')"
  app_version="$(required_release_label "$service" "org.opencontainers.image.version")"
  git_commit="$(required_release_label "$service" "org.opencontainers.image.revision")"
  echo "  $service image=$image_reference image_id=$image_id version=$app_version commit=$git_commit"
}

required_release_label() {
  service="$1"
  label="$2"
  container_id="$(compose ps -q "$service")"
  value="$(docker inspect "$container_id" --format "{{index .Config.Labels \"$label\"}}" 2>/dev/null || true)"
  case "$value" in
    ""|"<no value>"|"unknown")
      echo "Missing required OCI label for $service: $label" >&2
      return 1
      ;;
  esac
  printf '%s\n' "$value"
}

required_image_label() {
  image="$1"
  label="$2"
  value="$(docker image inspect "$image" --format "{{index .Config.Labels \"$label\"}}" 2>/dev/null || true)"
  case "$value" in
    ""|"<no value>"|"unknown")
      echo "Missing required OCI label for image $image: $label" >&2
      return 1
      ;;
  esac
  printf '%s\n' "$value"
}

preflight_release_images() {
  backend_version="$(required_image_label "$BACKEND_IMAGE" "org.opencontainers.image.version")"
  backend_revision="$(required_image_label "$BACKEND_IMAGE" "org.opencontainers.image.revision")"
  frontend_version="$(required_image_label "$FRONTEND_IMAGE" "org.opencontainers.image.version")"
  frontend_revision="$(required_image_label "$FRONTEND_IMAGE" "org.opencontainers.image.revision")"
  if [ "$backend_version" != "$frontend_version" ] || [ "$backend_revision" != "$frontend_revision" ]; then
    echo "Backend and frontend release identities do not match" >&2
    echo "  backend:  version=$backend_version revision=$backend_revision" >&2
    echo "  frontend: version=$frontend_version revision=$frontend_revision" >&2
    return 1
  fi
}

running_service_image_id() {
  service="$1"
  container_id="$(compose ps -q "$service" 2>/dev/null || true)"
  if [ -n "$container_id" ]; then
    docker inspect "$container_id" --format '{{.Image}}' 2>/dev/null || true
  fi
}

validate_split_runtime_mode() {
  split_runtime="false"
  if has_compose_service backend-worker; then
    split_runtime="true"
  fi

  runtime_role="${REPOGUARD_RUNTIME_ROLE:-}"
  if [ -z "$runtime_role" ]; then
    runtime_role="$(read_env_value REPOGUARD_RUNTIME_ROLE)"
  fi

  if [ -z "$runtime_role" ]; then
    legacy_worker_enabled="${REPOGUARD_WORKER_ENABLED:-}"
    if [ -z "$legacy_worker_enabled" ]; then
      legacy_worker_enabled="$(read_env_value REPOGUARD_WORKER_ENABLED)"
    fi
    case "$legacy_worker_enabled" in
      false|FALSE|False|0)
        runtime_role="api"
        ;;
      "")
        if [ "$split_runtime" = "true" ]; then
          runtime_role="api"
        else
          runtime_role="combined"
        fi
        ;;
      true|TRUE|True|1)
        runtime_role="combined"
        ;;
      *)
        echo "REPOGUARD_WORKER_ENABLED must be true, false, 1, or 0." >&2
        return 1
        ;;
    esac
    if [ -n "$legacy_worker_enabled" ]; then
      echo "REPOGUARD_WORKER_ENABLED is deprecated; resolved REPOGUARD_RUNTIME_ROLE=$runtime_role." >&2
    fi
  fi

  case "$runtime_role" in
    api|worker|combined)
      ;;
    *)
      echo "REPOGUARD_RUNTIME_ROLE must be api, worker, or combined." >&2
      return 1
      ;;
  esac

  deployment_mode="${REPOGUARD_DEPLOYMENT_MODE:-}"
  if [ -z "$deployment_mode" ]; then
    deployment_mode="$(read_env_value REPOGUARD_DEPLOYMENT_MODE)"
  fi
  expected_deployment_mode="monolith"
  if [ "$split_runtime" = "true" ]; then
    expected_deployment_mode="split"
  fi
  deployment_mode="${deployment_mode:-$expected_deployment_mode}"
  if [ "$deployment_mode" != "$expected_deployment_mode" ]; then
    echo "Compose services require REPOGUARD_DEPLOYMENT_MODE=$expected_deployment_mode." >&2
    return 1
  fi

  api_instance_count="${REPOGUARD_API_INSTANCE_COUNT:-}"
  if [ -z "$api_instance_count" ]; then
    api_instance_count="$(read_env_value REPOGUARD_API_INSTANCE_COUNT)"
  fi
  api_instance_count="${api_instance_count:-1}"
  case "$api_instance_count" in
    *[!0-9]*|"")
      echo "REPOGUARD_API_INSTANCE_COUNT must be a non-negative integer." >&2
      return 1
      ;;
  esac
  if [ "$api_instance_count" -ne 1 ]; then
    echo "The production API currently requires REPOGUARD_API_INSTANCE_COUNT=1 because API state is process-local." >&2
    return 1
  fi

  REPOGUARD_RUNTIME_ROLE="$runtime_role"
  REPOGUARD_DEPLOYMENT_MODE="$deployment_mode"
  REPOGUARD_API_INSTANCE_COUNT="$api_instance_count"
  export REPOGUARD_RUNTIME_ROLE
  export REPOGUARD_DEPLOYMENT_MODE
  export REPOGUARD_API_INSTANCE_COUNT

  if [ "$split_runtime" = "true" ]; then
    if [ "$runtime_role" = "api" ]; then
      return 0
    fi
    echo "Split deployment requires REPOGUARD_RUNTIME_ROLE=api for the API service." >&2
    return 1
  fi

  if [ "$runtime_role" = "combined" ]; then
    return 0
  fi
  echo "Monolithic deployment requires REPOGUARD_RUNTIME_ROLE=combined for the API service." >&2
  return 1
}

stop_inactive_split_worker() {
  if has_compose_service backend-worker; then
    return 0
  fi

  worker_container_id="$(docker ps -aq --filter 'name=^/repoguard-backend-worker$' | head -n 1)"
  if [ -z "$worker_container_id" ]; then
    return 0
  fi

  worker_service="$(docker inspect "$worker_container_id" --format '{{index .Config.Labels "com.docker.compose.service"}}' 2>/dev/null || true)"
  if [ "$worker_service" != "backend-worker" ]; then
    echo "Refusing to stop unexpected container named repoguard-backend-worker." >&2
    return 1
  fi

  if [ "$(docker inspect "$worker_container_id" --format '{{.State.Running}}' 2>/dev/null || true)" = "true" ]; then
    echo "Stopping inactive split Worker; the API service is handling Worker duties."
    docker stop -t 20 "$worker_container_id" >/dev/null
  fi
}

restore_deployment_assets() {
  if [ -z "$DEPLOY_ASSET_BACKUP_DIR" ]; then
    echo "No deployment asset backup was supplied; keeping the uploaded assets." >&2
    return 0
  fi
  if [ ! -d "$DEPLOY_ASSET_BACKUP_DIR" ]; then
    echo "Deployment asset backup is unavailable: $DEPLOY_ASSET_BACKUP_DIR" >&2
    return 0
  fi

  compose_directory="$(dirname "$COMPOSE_FILE")"
  restored_assets=false
  for relative_path in config/rabbitmq/rabbitmq.conf Caddyfile docker-compose.metrics-bridge.yml; do
    backup_path="${DEPLOY_ASSET_BACKUP_DIR}/${relative_path}"
    target_path="${compose_directory}/${relative_path}"
    if [ -f "$backup_path" ]; then
      mkdir -p "$(dirname "$target_path")"
      cp -p "$backup_path" "$target_path"
      restored_assets=true
    fi
  done
  if [ -f "$DEPLOY_ASSET_BACKUP_DIR/docker-compose.prod.yml" ]; then
    cp -p "$DEPLOY_ASSET_BACKUP_DIR/docker-compose.prod.yml" "$COMPOSE_FILE"
    restored_assets=true
  fi

  if [ "$restored_assets" = "true" ]; then
    echo "Previous deployment assets restored from $DEPLOY_ASSET_BACKUP_DIR." >&2
  else
    echo "No previous deployment assets existed; keeping the uploaded assets." >&2
  fi
}

rollback_deployment() {
  status="$1"
  trap - 0
  if [ "$status" -eq 0 ] || [ "${rollback_needed:-false}" != "true" ]; then
    exit "$status"
  fi

  set +e
  echo "Deployment failed; restoring the previous deployment assets and services..." >&2
  restore_deployment_assets
  compose up -d --no-deps mysql
  # Bind-file contents are not part of Compose's service hash. A forced broker
  # recreation is required to reload the restored consumer_timeout.
  compose up -d --no-deps --force-recreate rabbitmq
  wait_service_health mysql 45
  if wait_service_health rabbitmq 45; then
    record_rabbitmq_config_digest
  else
    invalidate_rabbitmq_config_digest
    echo "RabbitMQ rollback is unhealthy; the next deployment will force another recreation." >&2
  fi

  if [ -z "${previous_backend_image:-}" ]; then
    compose ps >&2
    echo "No previous backend image was running; infrastructure rollback attempt finished." >&2
    exit "$status"
  fi

  BACKEND_IMAGE="$previous_backend_image"
  export BACKEND_IMAGE
  if has_compose_service backend-worker; then
    compose stop backend-worker >/dev/null 2>&1
  fi
  compose up -d --no-deps backend
  wait_backend_health 45
  if has_compose_service backend-worker; then
    compose up -d --no-deps backend-worker
    wait_service_health backend-worker 45
  fi
  if [ -n "${previous_frontend_image:-}" ]; then
    FRONTEND_IMAGE="$previous_frontend_image"
    export FRONTEND_IMAGE
    compose up -d --no-deps frontend
    compose up -d --no-deps caddy
  fi
  compose ps >&2
  echo "Rollback attempt finished; deployment remains failed with status $status." >&2
  exit "$status"
}

rollback_needed=false
previous_backend_image=""
previous_frontend_image=""
trap 'rollback_deployment $?' 0

assert_same_image_id() {
  first_service="$1"
  second_service="$2"
  first_container_id="$(compose ps -q "$first_service")"
  second_container_id="$(compose ps -q "$second_service")"
  first_image_id="$(docker inspect "$first_container_id" --format '{{.Image}}')"
  second_image_id="$(docker inspect "$second_container_id" --format '{{.Image}}')"
  if [ "$first_image_id" != "$second_image_id" ]; then
    echo "Image ID mismatch between $first_service and $second_service" >&2
    echo "  $first_service: $first_image_id" >&2
    echo "  $second_service: $second_image_id" >&2
    return 1
  fi
}

assert_same_release_identity() {
  first_service="$1"
  second_service="$2"
  first_version="$(required_release_label "$first_service" "org.opencontainers.image.version")"
  second_version="$(required_release_label "$second_service" "org.opencontainers.image.version")"
  first_revision="$(required_release_label "$first_service" "org.opencontainers.image.revision")"
  second_revision="$(required_release_label "$second_service" "org.opencontainers.image.revision")"

  if [ "$first_version" != "$second_version" ] || [ "$first_revision" != "$second_revision" ]; then
    echo "Release identity mismatch between $first_service and $second_service" >&2
    echo "  $first_service: version=$first_version revision=$first_revision" >&2
    echo "  $second_service: version=$second_version revision=$second_revision" >&2
    return 1
  fi
}

verify_deployment() {
  wait_backend_health "${1:-30}"
  wait_http_health "${2:-30}"
  assert_service_image backend "$BACKEND_IMAGE"
  if has_compose_service backend-worker; then
    wait_service_health backend-worker "${1:-30}"
    assert_service_image backend-worker "$BACKEND_IMAGE"
    assert_same_image_id backend backend-worker
    assert_same_release_identity backend backend-worker
  fi
  assert_service_image frontend "$FRONTEND_IMAGE"
  echo "Deployment release identities:"
  print_service_release_identity backend
  if has_compose_service backend-worker; then
    print_service_release_identity backend-worker
  fi
  print_service_release_identity frontend
}

exec 9>"$DEPLOY_LOCK_FILE"
if ! flock -n 9; then
  echo "Another deployment already holds $DEPLOY_LOCK_FILE; refusing to run concurrently." >&2
  exit 1
fi

echo "Deploying RepoGuard images:"
echo "  backend:  $BACKEND_IMAGE"
echo "  frontend: $FRONTEND_IMAGE"
echo "  domains:  ${REPOGUARD_FRONTEND_SERVER_NAME:-}"

validate_required_bind_sources
validate_secret_files
validate_edge_observability_isolation
validate_split_runtime_mode
validate_production_data_routing
validate_review_timeout_layering
if [ "$PREFLIGHT_ONLY" = "true" ]; then
  echo "Production deployment preflight passed; no image was pulled and no service was changed."
  exit 0
fi
deploy_services="mysql rabbitmq backend frontend caddy"
if has_compose_service backend-worker; then
  deploy_services="mysql rabbitmq backend backend-worker frontend caddy"
fi
compose pull $deploy_services
preflight_release_images

previous_backend_image="$(running_service_image_id backend)"
previous_frontend_image="$(running_service_image_id frontend)"
rabbitmq_config_changed=false
if rabbitmq_config_requires_restart; then
  rabbitmq_config_changed=true
fi
rollback_needed=true

stop_inactive_split_worker

if [ -z "$(compose ps -q mysql rabbitmq 2>/dev/null)" ]; then
  if [ -n "$LEGACY_COMPOSE_FILE" ] && [ -f "$LEGACY_COMPOSE_FILE" ]; then
    if [ -n "$LEGACY_ENV_FILE" ] && [ -f "$LEGACY_ENV_FILE" ]; then
      docker compose --env-file "$LEGACY_ENV_FILE" -f "$LEGACY_COMPOSE_FILE" down
    else
      docker compose -f "$LEGACY_COMPOSE_FILE" down
    fi
  fi
fi

compose up -d --no-deps mysql
if [ "$rabbitmq_config_changed" = "true" ]; then
  echo "RabbitMQ configuration changed; recreating the broker to load it."
  compose up -d --no-deps --force-recreate rabbitmq
else
  compose up -d --no-deps rabbitmq
fi
wait_service_health mysql 45
wait_service_health rabbitmq 45

if has_compose_service backend-worker; then
  worker_container_id="$(compose ps -q backend-worker 2>/dev/null || true)"
  if [ -n "$worker_container_id" ]; then
    echo "Stopping the existing Worker before upgrading the API..."
    compose stop backend-worker
  fi
fi

compose up -d --no-deps backend
wait_backend_health 45

if has_compose_service backend-worker; then
  compose up -d --no-deps backend-worker
  wait_service_health backend-worker 45
fi

compose up -d --no-deps frontend
compose up -d --no-deps caddy
compose ps
verify_deployment 15 30
record_rabbitmq_config_digest

rollback_needed=false
echo "RepoGuard deployment is healthy: $HEALTH_URL"
