#!/usr/bin/env sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1/actuator/health}"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Missing compose file: $COMPOSE_FILE" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  echo "Create it from .env.prod.example and fill production secrets." >&2
  exit 1
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull backend frontend
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps

if command -v curl >/dev/null 2>&1; then
  curl -fsS "$HEALTH_URL" >/dev/null
else
  wget -q -O - "$HEALTH_URL" >/dev/null
fi

echo "RepoGuard deployment is healthy: $HEALTH_URL"
