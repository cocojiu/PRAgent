#!/usr/bin/env sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1/actuator/health}"
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

wait_health() {
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
  echo "Health check failed after $attempts attempts: $HEALTH_URL" >&2
  return 1
}

compose pull backend frontend

if [ -n "$(compose ps -q mysql rabbitmq 2>/dev/null)" ]; then
  compose up -d --no-deps backend
  wait_health 45
  compose up -d --no-deps frontend
  compose ps
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
wait_health 30

echo "RepoGuard deployment is healthy: $HEALTH_URL"
