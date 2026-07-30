#!/usr/bin/env bash

set -Eeuo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-repoguard-mysql}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/repoguard/backups/mysql}"
VERIFY_RESTORE="${VERIFY_RESTORE:-true}"
VERIFY_MEMORY_MB="${VERIFY_MEMORY_MB:-384}"
VERIFY_CPU_LIMIT="${VERIFY_CPU_LIMIT:-0.50}"

backup_partial=""
temporary_dir=""
verify_container=""
verify_volume=""

fail() {
  echo "ERROR=$1" >&2
  exit 1
}

cleanup() {
  local exit_code=$?
  set +e

  if [[ -n "${verify_container}" ]]; then
    case "${verify_container}" in
      repoguard-mysql-restore-*)
        docker rm -f "${verify_container}" >/dev/null 2>&1
        ;;
    esac
  fi

  if [[ -n "${verify_volume}" ]]; then
    case "${verify_volume}" in
      repoguard_mysql_restore_*)
        docker volume rm "${verify_volume}" >/dev/null 2>&1
        ;;
    esac
  fi

  if [[ -n "${backup_partial}" && -f "${backup_partial}" ]]; then
    rm -f -- "${backup_partial}"
  fi

  if [[ -n "${temporary_dir}" && -d "${temporary_dir}" ]]; then
    case "${temporary_dir}" in
      /tmp/repoguard-mysql-backup.*)
        rm -rf -- "${temporary_dir}"
        ;;
    esac
  fi

  unset BACKUP_ENCRYPTION_PASSWORD
  return "${exit_code}"
}
trap cleanup EXIT

for command_name in awk cut date df docker find gzip mktemp openssl seq sha256sum sleep tail wc; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "missing_required_command_${command_name}"
done

case "${MYSQL_CONTAINER}" in
  *[!A-Za-z0-9_.-]* | "")
    fail "invalid_mysql_container"
    ;;
esac

case "${VERIFY_RESTORE}" in
  true | false)
    ;;
  *)
    fail "verify_restore_must_be_true_or_false"
    ;;
esac

case "${VERIFY_MEMORY_MB}" in
  *[!0-9]* | "")
    fail "verify_memory_mb_must_be_numeric"
    ;;
esac

if (( VERIFY_MEMORY_MB < 320 || VERIFY_MEMORY_MB > 1024 )); then
  fail "verify_memory_mb_out_of_range"
fi

case "${VERIFY_CPU_LIMIT}" in
  0.25 | 0.50 | 0.75 | 1.00)
    ;;
  *)
    fail "verify_cpu_limit_not_allowed"
    ;;
esac

if [[ "${1:-}" != "--password-stdin" ]]; then
  fail "password_must_be_supplied_via_stdin"
fi

IFS= read -r BACKUP_ENCRYPTION_PASSWORD
export BACKUP_ENCRYPTION_PASSWORD
[[ ${#BACKUP_ENCRYPTION_PASSWORD} -ge 32 ]] \
  || fail "backup_encryption_password_too_short"

umask 077
mkdir -p -- "${BACKUP_ROOT}"
chmod 0700 -- "${BACKUP_ROOT}"
BACKUP_ROOT="$(cd -P -- "${BACKUP_ROOT}" && pwd)"

case "${BACKUP_ROOT}" in
  /opt/repoguard/backups | /opt/repoguard/backups/*)
    ;;
  *)
    fail "backup_root_outside_allowed_path"
    ;;
esac

legacy_plaintext_backup_count="$(
  find /opt/repoguard/backups \
    -maxdepth 1 \
    -type f \
    -name '*.sql' \
    -print \
    | wc -l \
    | awk '{ print $1 }'
)"

container_status="$(docker inspect --format '{{.State.Status}}' "${MYSQL_CONTAINER}" 2>/dev/null)" \
  || fail "mysql_container_not_found"
[[ "${container_status}" == "running" ]] || fail "mysql_container_not_running"

container_health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}unconfigured{{end}}' "${MYSQL_CONTAINER}")"
[[ "${container_health}" == "healthy" ]] || fail "mysql_container_not_healthy"

database_name="$(docker exec "${MYSQL_CONTAINER}" sh -lc 'printf "%s" "$MYSQL_DATABASE"')"
case "${database_name}" in
  *[!A-Za-z0-9_]* | "")
    fail "invalid_mysql_database_name"
    ;;
esac

mysql_query() {
  local container_name="$1"
  local query_database="$2"
  local sql="$3"

  docker exec "${container_name}" sh -lc \
    'if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
       mysql_root_password="$MYSQL_ROOT_PASSWORD"
     elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
       mysql_root_password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
     else
       echo "MySQL root password source is unavailable." >&2
       exit 64
     fi
     MYSQL_PWD="$mysql_root_password" exec mysql --batch --skip-column-names --user=root "$1" --execute="$2"' \
    sh "${query_database}" "${sql}"
}

dump_database() {
  local container_name="$1"
  local dump_database_name="$2"

  docker exec "${container_name}" sh -lc '
    if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
      mysql_root_password="$MYSQL_ROOT_PASSWORD"
    elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
      mysql_root_password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
    else
      echo "MySQL root password source is unavailable." >&2
      exit 64
    fi
    MYSQL_PWD="$mysql_root_password" exec mysqldump \
      --user=root \
      --single-transaction \
      --quick \
      --skip-lock-tables \
      --routines \
      --triggers \
      --events \
      --hex-blob \
      --order-by-primary \
      --skip-comments \
      --default-character-set=utf8mb4 \
      --set-gtid-purged=OFF \
      --column-statistics=0 \
      --no-tablespaces \
      --databases "$1"
  ' sh "${dump_database_name}"
}

read -r -d '' MANIFEST_SQL <<'SQL' || true
SET SESSION group_concat_max_len = 1048576;
SELECT GROUP_CONCAT(
  CONCAT(
    'SELECT ',
    QUOTE(TABLE_NAME),
    ' AS table_name, COUNT(*) AS row_count FROM `',
    REPLACE(TABLE_NAME, '`', '``'),
    '`'
  )
  ORDER BY TABLE_NAME
  SEPARATOR ' UNION ALL '
) INTO @manifest_body
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE';
SET @manifest_sql = IF(
  @manifest_body IS NULL,
  'SELECT ''__EMPTY__'' AS table_name, 0 AS row_count',
  CONCAT(
    'SELECT * FROM (',
    @manifest_body,
    ') AS row_manifest ORDER BY table_name'
  )
);
PREPARE row_manifest_statement FROM @manifest_sql;
EXECUTE row_manifest_statement;
DEALLOCATE PREPARE row_manifest_statement;
SQL

temporary_dir="$(mktemp -d /tmp/repoguard-mysql-backup.XXXXXX)"
source_manifest_file="${temporary_dir}/source.manifest"
restored_manifest_file="${temporary_dir}/restored.manifest"
table_check_log="${temporary_dir}/table-check.log"

mysql_version="$(mysql_query "${MYSQL_CONTAINER}" "${database_name}" 'SELECT VERSION();')"
source_stats="$(mysql_query "${MYSQL_CONTAINER}" "${database_name}" "
  SELECT CONCAT(
    COUNT(*), '|',
    COALESCE(SUM(TABLE_ROWS), 0), '|',
    COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0)
  )
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_TYPE = 'BASE TABLE';
")"
IFS='|' read -r source_table_count _ source_size_bytes <<<"${source_stats}"
for numeric_value in "${source_table_count}" "${source_size_bytes}"; do
  case "${numeric_value}" in
    *[!0-9]* | "")
      fail "invalid_source_statistics"
      ;;
  esac
done

non_transactional_tables="$(mysql_query "${MYSQL_CONTAINER}" "${database_name}" "
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_TYPE = 'BASE TABLE'
    AND ENGINE <> 'InnoDB';
")"
[[ "${non_transactional_tables}" == "0" ]] \
  || fail "non_transactional_tables_present"

mysql_query "${MYSQL_CONTAINER}" "${database_name}" "${MANIFEST_SQL}" >"${source_manifest_file}"
exact_row_count="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "${source_manifest_file}")"
source_table_names_sha256="$(
  cut -f 1 "${source_manifest_file}" \
    | sha256sum \
    | cut -d ' ' -f 1
)"
table_count="${source_table_count}"

available_kb="$(df -Pk "${BACKUP_ROOT}" | awk 'NR == 2 { print $4 }')"
case "${available_kb}" in
  *[!0-9]* | "")
    fail "invalid_available_disk_space"
    ;;
esac
required_kb=$(( (source_size_bytes * 3 / 1024) + 131072 ))
if (( available_kb < required_kb )); then
  fail "insufficient_backup_disk_space"
fi

timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
backup_name="repoguard-${timestamp}.sql.gz.enc"
backup_path="${BACKUP_ROOT}/${backup_name}"
backup_partial="${BACKUP_ROOT}/.${backup_name}.partial"
checksum_path="${backup_path}.sha256"
[[ ! -e "${backup_path}" && ! -e "${checksum_path}" ]] \
  || fail "backup_path_already_exists"

dump_database "${MYSQL_CONTAINER}" "${database_name}" \
  | gzip -9 \
  | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -md sha256 \
      -pass env:BACKUP_ENCRYPTION_PASSWORD \
      -out "${backup_partial}"

[[ -s "${backup_partial}" ]] || fail "encrypted_backup_is_empty"
mv -- "${backup_partial}" "${backup_path}"
backup_partial=""

backup_sha256="$(sha256sum "${backup_path}" | cut -d ' ' -f 1)"
printf '%s  %s\n' "${backup_sha256}" "${backup_name}" >"${checksum_path}"
(
  cd -- "${BACKUP_ROOT}"
  sha256sum --check --status "${backup_name}.sha256"
) || fail "encrypted_backup_checksum_failed"

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
  -pass env:BACKUP_ENCRYPTION_PASSWORD \
  -in "${backup_path}" \
  | gzip --test \
  || fail "encrypted_backup_stream_validation_failed"

logical_dump_sha256="$(
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
    -pass env:BACKUP_ENCRYPTION_PASSWORD \
    -in "${backup_path}" \
    | gzip -dc \
    | sha256sum \
    | cut -d ' ' -f 1
)"

restore_verified="skipped"
if [[ "${VERIFY_RESTORE}" == "true" ]]; then
  mem_available_kb="$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)"
  case "${mem_available_kb}" in
    *[!0-9]* | "")
      fail "invalid_available_memory"
      ;;
  esac
  required_memory_kb=$(( (VERIFY_MEMORY_MB + 128) * 1024 ))
  if (( mem_available_kb < required_memory_kb )); then
    fail "insufficient_memory_for_isolated_restore"
  fi

  mysql_image="$(docker inspect --format '{{.Config.Image}}' "${MYSQL_CONTAINER}")"
  verify_suffix="$(date -u +'%Y%m%d%H%M%S')-$$"
  verify_container="repoguard-mysql-restore-${verify_suffix}"
  verify_volume="repoguard_mysql_restore_${verify_suffix}"
  verify_password="$(openssl rand -hex 32)"

  docker volume create \
    --label com.repoguard.purpose=mysql-restore-verification \
    "${verify_volume}" >/dev/null

  docker run --detach \
    --name "${verify_container}" \
    --network none \
    --restart no \
    --memory "${VERIFY_MEMORY_MB}m" \
    --memory-swap "${VERIFY_MEMORY_MB}m" \
    --cpus "${VERIFY_CPU_LIMIT}" \
    --env "MYSQL_ROOT_PASSWORD=${verify_password}" \
    --volume "${verify_volume}:/var/lib/mysql" \
    "${mysql_image}" \
    --innodb-buffer-pool-size=64M \
    --max-connections=10 \
    --performance-schema=OFF >/dev/null
  unset verify_password

  restore_ready="false"
  for _ in $(seq 1 90); do
    verify_state="$(docker inspect --format '{{.State.Status}}' "${verify_container}")"
    if [[ "${verify_state}" == "exited" || "${verify_state}" == "dead" ]]; then
      fail "isolated_mysql_exited_before_ready"
    fi
    if docker exec "${verify_container}" sh -lc \
      'if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
         mysql_root_password="$MYSQL_ROOT_PASSWORD"
       elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
         mysql_root_password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
       else
         exit 64
       fi
       MYSQL_PWD="$mysql_root_password" mysql \
        --batch \
        --skip-column-names \
        --connect-timeout=2 \
        --user=root \
        --execute="SELECT 1"' \
      >/dev/null 2>&1; then
      restore_ready="true"
      break
    fi
    sleep 2
  done
  [[ "${restore_ready}" == "true" ]] || fail "isolated_mysql_readiness_timeout"

  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
    -pass env:BACKUP_ENCRYPTION_PASSWORD \
    -in "${backup_path}" \
    | gzip -dc \
    | docker exec --interactive "${verify_container}" sh -lc \
        'if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
           mysql_root_password="$MYSQL_ROOT_PASSWORD"
         elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
           mysql_root_password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
         else
           exit 64
         fi
         MYSQL_PWD="$mysql_root_password" exec mysql --user=root --binary-mode=1'

  mysql_query "${verify_container}" "${database_name}" "${MANIFEST_SQL}" >"${restored_manifest_file}"
  exact_row_count="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "${restored_manifest_file}")"
  table_count="$(mysql_query "${verify_container}" "${database_name}" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  case "${table_count}" in
    *[!0-9]* | "")
      fail "invalid_restored_table_count"
      ;;
  esac
  [[ "${table_count}" == "${source_table_count}" ]] \
    || fail "restored_table_count_mismatch"
  restored_table_names_sha256="$(
    cut -f 1 "${restored_manifest_file}" \
      | sha256sum \
      | cut -d ' ' -f 1
  )"
  [[ "${restored_table_names_sha256}" == "${source_table_names_sha256}" ]] \
    || fail "restored_table_names_mismatch"

  : >"${table_check_log}"
  checked_table_count=0
  if (( table_count > 0 )); then
    while IFS=$'\t' read -r table_name _; do
      case "${table_name}" in
        *[!A-Za-z0-9_]* | "")
          fail "unsupported_restored_table_name"
          ;;
      esac

      if mysql_query \
        "${verify_container}" \
        "${database_name}" \
        "CHECK TABLE \`${table_name}\`;" \
        >>"${table_check_log}" 2>&1; then
        checked_table_count=$((checked_table_count + 1))
      else
        echo "TABLE_CHECK_DIAGNOSTIC_BEGIN" >&2
        tail -n 40 "${table_check_log}" >&2
        echo "TABLE_CHECK_DIAGNOSTIC_END" >&2
        fail "isolated_table_check_command_failed"
      fi
    done <"${restored_manifest_file}"
  fi

  (( checked_table_count == table_count )) \
    || fail "isolated_table_check_count_mismatch"
  if (( table_count > 0 )) && ! awk -F '\t' '
    NF != 4 || $2 != "check" || $3 != "status" || $4 != "OK" {
      invalid = 1
    }
    END {
      exit invalid ? 1 : 0
    }
  ' "${table_check_log}"; then
    echo "TABLE_CHECK_DIAGNOSTIC_BEGIN" >&2
    tail -n 40 "${table_check_log}" >&2
    echo "TABLE_CHECK_DIAGNOSTIC_END" >&2
    fail "isolated_table_check_failed"
  fi

  docker rm -f "${verify_container}" >/dev/null
  verify_container=""
  docker volume rm "${verify_volume}" >/dev/null
  verify_volume=""
  restore_verified="true"
fi

backup_bytes="$(wc -c <"${backup_path}" | awk '{ print $1 }')"
key_fingerprint="$(printf '%s' "${BACKUP_ENCRYPTION_PASSWORD}" | sha256sum | cut -d ' ' -f 1)"

echo "BACKUP_TIMESTAMP_UTC=${timestamp}"
echo "MYSQL_VERSION=${mysql_version}"
echo "DATABASE_NAME=${database_name}"
echo "TABLE_COUNT=${table_count}"
echo "EXACT_ROW_COUNT=${exact_row_count}"
echo "SOURCE_SIZE_BYTES=${source_size_bytes}"
echo "BACKUP_BYTES=${backup_bytes}"
echo "BACKUP_PATH=${backup_path}"
echo "BACKUP_SHA256=${backup_sha256}"
echo "LOGICAL_DUMP_SHA256=${logical_dump_sha256}"
echo "KEY_FINGERPRINT=${key_fingerprint}"
echo "LEGACY_PLAINTEXT_BACKUP_COUNT=${legacy_plaintext_backup_count}"
echo "RESTORE_VERIFIED=${restore_verified}"
