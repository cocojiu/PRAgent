#!/usr/bin/env bash

set -Eeuo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-repoguard-mysql}"
BINLOG_ROOT="${BINLOG_ROOT:-/opt/repoguard/backups/mysql-binlog}"

fail() {
  echo "ERROR=$1" >&2
  exit 1
}

validate_binlog_name() {
  [[ "$1" =~ ^mysql-bin\.[0-9]{6}$ ]] || fail "invalid_binlog_name"
}

case "${BINLOG_ROOT}" in
  /opt/repoguard/backups/mysql-binlog | /opt/repoguard/backups/mysql-binlog/*) ;;
  *) fail "binlog_root_outside_allowed_path" ;;
esac

umask 077
install -m 700 -d "${BINLOG_ROOT}"

if [[ "${1:-}" == "--ack" ]]; then
  acknowledged="${2:-}"
  validate_binlog_name "${acknowledged}"
  printf '%s\n' "${acknowledged}" >"${BINLOG_ROOT}/.last-offsite-binlog.tmp"
  mv -- "${BINLOG_ROOT}/.last-offsite-binlog.tmp" "${BINLOG_ROOT}/.last-offsite-binlog"
  echo "BINLOG_ACKNOWLEDGED=${acknowledged}"
  exit 0
fi

[[ "${1:-}" == "--password-stdin" ]] || fail "password_must_be_supplied_via_stdin"
IFS= read -r BACKUP_ENCRYPTION_PASSWORD
export BACKUP_ENCRYPTION_PASSWORD
[[ ${#BACKUP_ENCRYPTION_PASSWORD} -ge 32 ]] || fail "backup_encryption_password_too_short"

for command_name in awk cut date docker flock mktemp openssl sha256sum tar; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "missing_required_command_${command_name}"
done

exec 9>"${BINLOG_ROOT}/.archive.lock"
flock -n 9 || fail "binlog_archive_already_running"

container_status="$(docker inspect --format '{{.State.Status}}' "${MYSQL_CONTAINER}" 2>/dev/null)" \
  || fail "mysql_container_not_found"
[[ "${container_status}" == "running" ]] || fail "mysql_container_not_running"

mysql_root() {
  local sql="$1"
  docker exec "${MYSQL_CONTAINER}" sh -lc '
    if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
      password="$MYSQL_ROOT_PASSWORD"
    elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
      password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
    else
      exit 64
    fi
    MYSQL_PWD="$password" exec mysql --batch --skip-column-names --user=root --execute="$1"
  ' sh "${sql}"
}

[[ "$(mysql_root "SELECT @@GLOBAL.log_bin;")" == "1" ]] || fail "mysql_binary_logging_disabled"
mysql_root "FLUSH BINARY LOGS;"
mapfile -t binlogs < <(mysql_root "SHOW BINARY LOGS;" | awk '{print $1}')
(( ${#binlogs[@]} >= 2 )) || fail "no_closed_binlog_available"

active_binlog="${binlogs[${#binlogs[@]}-1]}"
validate_binlog_name "${active_binlog}"
last_offsite=""
if [[ -f "${BINLOG_ROOT}/.last-offsite-binlog" ]]; then
  IFS= read -r last_offsite <"${BINLOG_ROOT}/.last-offsite-binlog"
  validate_binlog_name "${last_offsite}"
fi

archive_files=()
for binlog in "${binlogs[@]}"; do
  validate_binlog_name "${binlog}"
  [[ "${binlog}" == "${active_binlog}" ]] && continue
  if [[ -z "${last_offsite}" || "${binlog}" > "${last_offsite}" ]]; then
    archive_files+=("${binlog}")
  fi
done

if (( ${#archive_files[@]} == 0 )); then
  echo "BINLOG_ARCHIVE_EMPTY=true"
  echo "BINLOG_ACTIVE_FILE=${active_binlog}"
  exit 0
fi

temporary_dir="$(mktemp -d /tmp/repoguard-mysql-binlog.XXXXXX)"
partial_path=""
cleanup() {
  local exit_code=$?
  set +e
  [[ -n "${partial_path}" && -f "${partial_path}" ]] && rm -f -- "${partial_path}"
  case "${temporary_dir}" in
    /tmp/repoguard-mysql-binlog.*) rm -rf -- "${temporary_dir}" ;;
  esac
  unset BACKUP_ENCRYPTION_PASSWORD
  return "${exit_code}"
}
trap cleanup EXIT

for binlog in "${archive_files[@]}"; do
  docker cp "${MYSQL_CONTAINER}:/var/lib/mysql/${binlog}" "${temporary_dir}/${binlog}" >/dev/null
  [[ -s "${temporary_dir}/${binlog}" ]] || fail "copied_binlog_is_empty"
done

first_binlog="${archive_files[0]}"
last_binlog="${archive_files[${#archive_files[@]}-1]}"
timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
archive_name="repoguard-binlog-${first_binlog#mysql-bin.}-${last_binlog#mysql-bin.}-${timestamp}.tar.gz.enc"
archive_path="${BINLOG_ROOT}/${archive_name}"
partial_path="${BINLOG_ROOT}/.${archive_name}.partial"

tar -C "${temporary_dir}" -czf - -- "${archive_files[@]}" \
  | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -md sha256 \
      -pass env:BACKUP_ENCRYPTION_PASSWORD -out "${partial_path}"
[[ -s "${partial_path}" ]] || fail "encrypted_binlog_archive_is_empty"
mv -- "${partial_path}" "${archive_path}"
partial_path=""
archive_sha256="$(sha256sum "${archive_path}" | cut -d ' ' -f 1)"
printf '%s  %s\n' "${archive_sha256}" "${archive_name}" >"${archive_path}.sha256"

echo "BINLOG_ARCHIVE_EMPTY=false"
echo "BINLOG_ARCHIVE_PATH=${archive_path}"
echo "BINLOG_ARCHIVE_SHA256=${archive_sha256}"
echo "BINLOG_FILE_COUNT=${#archive_files[@]}"
echo "BINLOG_FIRST_FILE=${first_binlog}"
echo "BINLOG_LAST_FILE=${last_binlog}"
echo "BINLOG_ACTIVE_FILE=${active_binlog}"
