#!/usr/bin/env bash
set -euo pipefail

start_pr="${1:?usage: verify-worker-comparison.sh START_PR COUNT}"
count="${2:?usage: verify-worker-comparison.sh START_PR COUNT}"
end_pr=$((start_pr + count - 1))

docker exec repoguard-mysql sh -lc \
  'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "
    SET @rownum := 0;
    SELECT '\''range'\'', '"$start_pr"', '"$end_pr"';
    SELECT '\''total_tasks'\'', COUNT(1)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"';
    SELECT '\''distinct_prs'\'', COUNT(DISTINCT pr_number)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"';
    SELECT status, COUNT(1)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
     GROUP BY status
     ORDER BY status;
    SELECT '\''duplicate_pr_groups'\'', COUNT(1)
      FROM (
        SELECT pr_number
          FROM review_task
         WHERE organization = '\''pt-org'\''
           AND repository = '\''repo-01'\''
           AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
         GROUP BY pr_number
        HAVING COUNT(1) > 1
      ) d;
    SELECT '\''unfinished_tasks'\'', COUNT(1)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
       AND status NOT IN ('\''COMPLETED'\'', '\''FAILED'\'', '\''CANCELLED'\'');
    SELECT '\''avg_duration_seconds'\'', ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, finished_at) / 1000000), 3)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
       AND finished_at IS NOT NULL;
    SELECT '\''max_duration_seconds'\'', ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, created_at, finished_at) / 1000000), 3)
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
       AND finished_at IS NOT NULL;
    SELECT '\''p95_duration_seconds'\'', ROUND(duration_seconds, 3)
      FROM (
        SELECT TIMESTAMPDIFF(MICROSECOND, created_at, finished_at) / 1000000 AS duration_seconds,
               ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND, created_at, finished_at)) AS rn,
               COUNT(*) OVER () AS total
          FROM review_task
         WHERE organization = '\''pt-org'\''
           AND repository = '\''repo-01'\''
           AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
           AND finished_at IS NOT NULL
      ) ranked
     WHERE rn = CEIL(total * 0.95)
     LIMIT 1;
    SELECT '\''first_created'\'', DATE_FORMAT(MIN(created_at), '\''%Y-%m-%d %H:%i:%s'\'')
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"';
    SELECT '\''last_finished'\'', DATE_FORMAT(MAX(finished_at), '\''%Y-%m-%d %H:%i:%s'\'')
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"';
    SELECT '\''clear_seconds'\'', TIMESTAMPDIFF(SECOND, MIN(created_at), MAX(finished_at))
      FROM review_task
     WHERE organization = '\''pt-org'\''
       AND repository = '\''repo-01'\''
       AND pr_number BETWEEN '"$start_pr"' AND '"$end_pr"'
       AND finished_at IS NOT NULL;
  "' 2>/dev/null

printf '%s\n' '---RABBITMQ---'
docker exec repoguard-rabbitmq rabbitmqctl list_queues \
  -p /repoguard -q name messages_ready messages_unacknowledged consumers

printf '%s\n' '---CONSUMERS---'
docker exec repoguard-rabbitmq rabbitmqctl list_consumers -p /repoguard -q \
  | awk '$1 == "repoguard.review.queue.v2" { count++ } END { print "review_consumers", count + 0 }'

printf '%s\n' '---RESOURCES---'
docker stats --no-stream \
  --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.PIDs}}'
