#!/usr/bin/env sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1/actuator/health}"
BACKEND_SERVICE="${BACKEND_SERVICE:-backend}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
LEGACY_COMPOSE_FILE="${LEGACY_COMPOSE_FILE:-}"
LEGACY_ENV_FILE="${LEGACY_ENV_FILE:-}"

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

if [ -z "${COMPOSE_PROFILES:-}" ]; then
  COMPOSE_PROFILES="$(read_env_value COMPOSE_PROFILES)"
fi

export COMPOSE_PROJECT_NAME
export COMPOSE_PROFILES

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
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
  api_worker_enabled="${REPOGUARD_WORKER_ENABLED:-}"
  if [ -z "$api_worker_enabled" ]; then
    api_worker_enabled="$(read_env_value REPOGUARD_WORKER_ENABLED)"
  fi

  if has_compose_service backend-worker; then
    case "$api_worker_enabled" in
      false|FALSE|False|0)
        return 0
        ;;
    esac
    echo "Split deployment requires REPOGUARD_WORKER_ENABLED=false for the API service." >&2
    return 1
  fi

  case "$api_worker_enabled" in
    ""|true|TRUE|True|1)
      return 0
      ;;
  esac
  echo "Monolithic deployment requires REPOGUARD_WORKER_ENABLED=true for the API service." >&2
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

rollback_deployment() {
  status="$1"
  trap - 0
  if [ "$status" -eq 0 ] || [ "${rollback_needed:-false}" != "true" ] || [ -z "${previous_backend_image:-}" ]; then
    exit "$status"
  fi

  set +e
  echo "Deployment failed; restoring the previous application images..." >&2
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

echo "Deploying RepoGuard images:"
echo "  backend:  $BACKEND_IMAGE"
echo "  frontend: $FRONTEND_IMAGE"
echo "  domains:  ${REPOGUARD_FRONTEND_SERVER_NAME:-}"

validate_production_data_routing
validate_split_runtime_mode
stop_inactive_split_worker
deploy_services="mysql rabbitmq backend frontend caddy"
if has_compose_service backend-worker; then
  deploy_services="mysql rabbitmq backend backend-worker frontend caddy"
fi
compose pull $deploy_services
preflight_release_images

previous_backend_image="$(running_service_image_id backend)"
previous_frontend_image="$(running_service_image_id frontend)"

if [ -z "$(compose ps -q mysql rabbitmq 2>/dev/null)" ]; then
  if [ -n "$LEGACY_COMPOSE_FILE" ] && [ -f "$LEGACY_COMPOSE_FILE" ]; then
    if [ -n "$LEGACY_ENV_FILE" ] && [ -f "$LEGACY_ENV_FILE" ]; then
      docker compose --env-file "$LEGACY_ENV_FILE" -f "$LEGACY_COMPOSE_FILE" down
    else
      docker compose -f "$LEGACY_COMPOSE_FILE" down
    fi
  fi
fi

compose up -d --no-deps mysql rabbitmq
wait_service_health mysql 45
wait_service_health rabbitmq 45

rollback_needed=true

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

rollback_needed=false
echo "RepoGuard deployment is healthy: $HEALTH_URL"
