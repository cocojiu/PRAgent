#!/usr/bin/env bash

set -Eeuo pipefail

TARGET_CONTAINER="${TARGET_CONTAINER:-}"
FULL_BACKUP_PATH="${FULL_BACKUP_PATH:-}"
BINLOG_ARCHIVE_DIRECTORY="${BINLOG_ARCHIVE_DIRECTORY:-}"
STOP_DATETIME_UTC="${STOP_DATETIME_UTC:-}"

fail() { echo "ERROR=$1" >&2; exit 1; }

[[ "${1:-}" == "--password-stdin" ]] || fail "password_must_be_supplied_via_stdin"
IFS= read -r BACKUP_ENCRYPTION_PASSWORD
export BACKUP_ENCRYPTION_PASSWORD
[[ ${#BACKUP_ENCRYPTION_PASSWORD} -ge 32 ]] || fail "backup_encryption_password_too_short"
[[ "${TARGET_CONTAINER}" =~ ^repoguard-mysql-pitr-[A-Za-z0-9_.-]+$ ]] || fail "unsafe_target_container"
[[ -f "${FULL_BACKUP_PATH}" ]] || fail "full_backup_not_found"
[[ -d "${BINLOG_ARCHIVE_DIRECTORY}" ]] || fail "binlog_archive_directory_not_found"
[[ "${STOP_DATETIME_UTC}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}$ ]] \
  || fail "stop_datetime_must_be_utc_mysql_format"
for command_name in docker find grep gzip mktemp mysqlbinlog openssl sed sort tar; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "missing_required_command_${command_name}"
done

temporary_dir="$(mktemp -d /tmp/repoguard-mysql-pitr.XXXXXX)"
cleanup() {
  local exit_code=$?
  set +e
  case "${temporary_dir}" in /tmp/repoguard-mysql-pitr.*) rm -rf -- "${temporary_dir}" ;; esac
  unset BACKUP_ENCRYPTION_PASSWORD
  return "${exit_code}"
}
trap cleanup EXIT

dump_path="${temporary_dir}/base.sql"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
  -pass env:BACKUP_ENCRYPTION_PASSWORD -in "${FULL_BACKUP_PATH}" | gzip -dc >"${dump_path}"

source_line="$(grep -m1 -E '^-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE=' "${dump_path}" || true)"
[[ -n "${source_line}" ]] || fail "base_backup_has_no_binlog_coordinates"
start_file="$(sed -E "s/.*SOURCE_LOG_FILE='([^']+)'.*/\1/" <<<"${source_line}")"
start_position="$(sed -E 's/.*SOURCE_LOG_POS=([0-9]+).*/\1/' <<<"${source_line}")"
[[ "${start_file}" =~ ^mysql-bin\.[0-9]{6}$ && "${start_position}" =~ ^[0-9]+$ ]] \
  || fail "invalid_base_backup_binlog_coordinates"

while IFS= read -r -d '' archive; do
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
    -pass env:BACKUP_ENCRYPTION_PASSWORD -in "${archive}" | tar -xzf - -C "${temporary_dir}"
done < <(find "${BINLOG_ARCHIVE_DIRECTORY}" -maxdepth 1 -type f -name 'repoguard-binlog-*.tar.gz.enc' -print0 | sort -z)

mapfile -t binlogs < <(find "${temporary_dir}" -maxdepth 1 -type f -name 'mysql-bin.[0-9][0-9][0-9][0-9][0-9][0-9]' -print | sort -u)
(( ${#binlogs[@]} > 0 )) || fail "no_binlogs_extracted"

docker exec -i "${TARGET_CONTAINER}" sh -lc '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --binary-mode=1
' <"${dump_path}"

started=false
for binlog in "${binlogs[@]}"; do
  name="${binlog##*/}"
  if [[ "${started}" == "false" && "${name}" < "${start_file}" ]]; then
    continue
  fi
  if [[ "${started}" == "false" ]]; then
    [[ "${name}" == "${start_file}" ]] || fail "starting_binlog_not_found"
    TZ=UTC mysqlbinlog --start-position="${start_position}" --stop-datetime="${STOP_DATETIME_UTC}" "${binlog}" \
      | docker exec -i "${TARGET_CONTAINER}" sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --binary-mode=1'
    started=true
  else
    TZ=UTC mysqlbinlog --stop-datetime="${STOP_DATETIME_UTC}" "${binlog}" \
      | docker exec -i "${TARGET_CONTAINER}" sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --binary-mode=1'
  fi
done
[[ "${started}" == "true" ]] || fail "starting_binlog_not_found"

echo "PITR_RESTORED=true"
echo "PITR_START_FILE=${start_file}"
echo "PITR_START_POSITION=${start_position}"
echo "PITR_STOP_DATETIME_UTC=${STOP_DATETIME_UTC}"
