#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.prod}"

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

"${compose[@]}" exec -T mysql sh -ec '
  password="$(cat /run/secrets/mysql.root-password)"
  exec mysql --protocol=socket -uroot --password="$password" --batch --raw --table "$MYSQL_DATABASE" <<"SQL"
select
  object_schema,
  object_name,
  index_name,
  count_star as operations,
  count_read,
  count_write
from performance_schema.table_io_waits_summary_by_index_usage
where object_schema = database()
order by count_star asc, object_name, index_name;

select
  table_name,
  index_name,
  non_unique,
  is_visible,
  group_concat(column_name order by seq_in_index separator ",") as indexed_columns
from information_schema.statistics
where table_schema = database()
group by table_name, index_name, non_unique, is_visible
order by table_name, index_name;
SQL
'
