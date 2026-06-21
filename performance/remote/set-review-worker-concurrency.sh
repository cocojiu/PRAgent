#!/usr/bin/env bash
set -euo pipefail

concurrency="${1:?usage: set-review-worker-concurrency.sh CONCURRENCY}"
case "$concurrency" in
  1|2|4) ;;
  *) echo "concurrency must be 1, 2, or 4" >&2; exit 2 ;;
esac

cd /opt/repoguard
if [ ! -f .env.pre-worker-concurrency-test ]; then
  cp .env .env.pre-worker-concurrency-test
fi

current_backend_image="$(docker inspect repoguard-backend --format '{{.Config.Image}}')"
if grep -q '^BACKEND_IMAGE=' .env; then
  sed -i "s|^BACKEND_IMAGE=.*|BACKEND_IMAGE=${current_backend_image}|" .env
else
  printf '\nBACKEND_IMAGE=%s\n' "$current_backend_image" >> .env
fi

if grep -q '^REPOGUARD_REVIEW_WORKER_CONCURRENCY=' .env; then
  sed -i "s/^REPOGUARD_REVIEW_WORKER_CONCURRENCY=.*/REPOGUARD_REVIEW_WORKER_CONCURRENCY=${concurrency}/" .env
else
  printf '\nREPOGUARD_REVIEW_WORKER_CONCURRENCY=%s\n' "$concurrency" >> .env
fi

cat > docker-compose.worker-concurrency.yml <<YAML
services:
  backend:
    environment:
      REPOGUARD_REVIEW_WORKER_CONCURRENCY: "${concurrency}"
      SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY: "${concurrency}"
      SPRING_RABBITMQ_LISTENER_SIMPLE_MAX_CONCURRENCY: "${concurrency}"
YAML

docker compose -f docker-compose.prod.yml -f docker-compose.worker-concurrency.yml --env-file .env up -d --no-deps --pull never backend >/dev/null

for _ in $(seq 1 40); do
  if curl -fsS http://127.0.0.1/actuator/health | grep -q '"status":"UP"'; then
    break
  fi
  sleep 3
done

printf 'backend_env_concurrency='
docker exec repoguard-backend printenv REPOGUARD_REVIEW_WORKER_CONCURRENCY
printf 'review_consumers='
docker exec repoguard-rabbitmq rabbitmqctl list_consumers -p /repoguard -q \
  | awk '$1 == "repoguard.review.queue.v2" { count++ } END { print count + 0 }'
