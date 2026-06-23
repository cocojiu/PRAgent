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

if [ -z "$LEGACY_COMPOSE_FILE" ]; then
  LEGACY_COMPOSE_FILE="$(read_env_value LEGACY_COMPOSE_FILE)"
fi

if [ -z "$LEGACY_ENV_FILE" ]; then
  LEGACY_ENV_FILE="$(read_env_value LEGACY_ENV_FILE)"
fi

if [ -z "$COMPOSE_PROJECT_NAME" ]; then
  COMPOSE_PROJECT_NAME="$(read_env_value COMPOSE_PROJECT_NAME)"
fi

export COMPOSE_PROJECT_NAME

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

print_backend_logs() {
  echo "Recent backend logs:" >&2
  compose logs --tail=120 backend >&2 || true
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

verify_deployment() {
  wait_backend_health "${1:-30}"
  wait_http_health "${2:-30}"
  assert_service_image backend "$BACKEND_IMAGE"
  assert_service_image frontend "$FRONTEND_IMAGE"
}

echo "Deploying RepoGuard images:"
echo "  backend:  $BACKEND_IMAGE"
echo "  frontend: $FRONTEND_IMAGE"

compose pull backend frontend

if [ -n "$(compose ps -q mysql rabbitmq 2>/dev/null)" ]; then
  compose up -d --no-deps backend
  wait_backend_health 45
  compose up -d --no-deps frontend
  compose ps
  verify_deployment 15 30
  echo "RepoGuard deployment is healthy: $HEALTH_URL"
  exit 0
fi

if [ -n "$LEGACY_COMPOSE_FILE" ] && [ -f "$LEGACY_COMPOSE_FILE" ]; then
  if [ -n "$LEGACY_ENV_FILE" ] && [ -f "$LEGACY_ENV_FILE" ]; then
    docker compose --env-file "$LEGACY_ENV_FILE" -f "$LEGACY_COMPOSE_FILE" down
  else
    docker compose -f "$LEGACY_COMPOSE_FILE" down
  fi
fi

compose up -d
compose ps
verify_deployment 30 30

echo "RepoGuard deployment is healthy: $HEALTH_URL"
