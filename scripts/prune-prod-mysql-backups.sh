#!/usr/bin/env bash

set -Eeuo pipefail

BACKUP_ROOT="/opt/repoguard/backups/mysql"
LEGACY_BACKUP_ROOT="/opt/repoguard/backups/mysql/legacy"
LOCK_DIRECTORY="/opt/repoguard/backups/mysql/.retention.lock"
RETENTION_COUNT=7
CURRENT_BACKUP_PATH="${CURRENT_BACKUP_PATH:-}"
CURRENT_BACKUP_SHA256="${CURRENT_BACKUP_SHA256:-}"

lock_acquired="false"

fail() {
  echo "ERROR=$1" >&2
  exit 1
}

report_unexpected_error() {
  local exit_code=$?
  local line_number="$1"

  echo "ERROR=unexpected_failure" >&2
  echo "ERROR_LINE=${line_number}" >&2
  exit "${exit_code}"
}

cleanup() {
  local exit_code=$?
  set +e

  if [[ "${lock_acquired}" == "true" ]]; then
    case "${LOCK_DIRECTORY}" in
      /opt/repoguard/backups/mysql/.retention.lock)
        rmdir -- "${LOCK_DIRECTORY}" >/dev/null 2>&1
        ;;
    esac
  fi

  return "${exit_code}"
}
trap 'report_unexpected_error "${LINENO}"' ERR
trap cleanup EXIT

for command_name in awk chmod date find mkdir rm rmdir sha256sum sort stat; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "missing_required_command_${command_name}"
done

[[ $# -eq 0 ]] || fail "retention_operation_accepts_no_arguments"
[[ -d "${BACKUP_ROOT}" ]] || fail "backup_root_not_found"
BACKUP_ROOT="$(cd -P -- "${BACKUP_ROOT}" && pwd)"
[[ "${BACKUP_ROOT}" == "/opt/repoguard/backups/mysql" ]] \
  || fail "backup_root_outside_allowed_path"

[[ -d "${LEGACY_BACKUP_ROOT}" ]] || fail "legacy_backup_root_not_found"
LEGACY_BACKUP_ROOT="$(cd -P -- "${LEGACY_BACKUP_ROOT}" && pwd)"
[[ "${LEGACY_BACKUP_ROOT}" == "/opt/repoguard/backups/mysql/legacy" ]] \
  || fail "legacy_backup_root_outside_allowed_path"

current_backup_basename="${CURRENT_BACKUP_PATH##*/}"
[[ "${CURRENT_BACKUP_PATH}" == "${BACKUP_ROOT}/${current_backup_basename}" ]] \
  || fail "current_backup_not_direct_child"
[[ "${current_backup_basename}" =~ ^repoguard-[0-9]{8}T[0-9]{6}Z\.sql\.gz\.enc$ ]] \
  || fail "invalid_current_backup_name"
[[ "${CURRENT_BACKUP_SHA256}" =~ ^[0-9a-f]{64}$ ]] \
  || fail "invalid_current_backup_sha256"
[[ -f "${CURRENT_BACKUP_PATH}" && ! -L "${CURRENT_BACKUP_PATH}" ]] \
  || fail "current_backup_not_found"

umask 077
mkdir -- "${LOCK_DIRECTORY}" || fail "retention_lock_unavailable"
lock_acquired="true"
chmod 0700 -- "${LOCK_DIRECTORY}"

mapfile -d '' -t backup_paths < <(
  find "${BACKUP_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name 'repoguard-*.sql.gz.enc' \
    -print0 \
    | LC_ALL=C sort -z -r
)
mapfile -d '' -t checksum_paths < <(
  find "${BACKUP_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name 'repoguard-*.sql.gz.enc.sha256' \
    -print0 \
    | LC_ALL=C sort -z -r
)

backup_count_before="${#backup_paths[@]}"
checksum_count_before="${#checksum_paths[@]}"
(( backup_count_before > 0 )) || fail "no_daily_backups_found"
[[ "${checksum_count_before}" -eq "${backup_count_before}" ]] \
  || fail "daily_backup_checksum_pair_count_mismatch"

declare -a backup_hashes=()
declare -a backup_stats=()

verified_backup_count=0
current_backup_match_count=0

for backup_path in "${backup_paths[@]}"; do
  backup_basename="${backup_path##*/}"
  [[ "${backup_path}" == "${BACKUP_ROOT}/${backup_basename}" ]] \
    || fail "daily_backup_not_direct_child"
  [[ "${backup_basename}" =~ ^repoguard-[0-9]{8}T[0-9]{6}Z\.sql\.gz\.enc$ ]] \
    || fail "invalid_daily_backup_name"
  [[ ! -L "${backup_path}" ]] || fail "daily_backup_symlink_not_allowed"

  checksum_path="${backup_path}.sha256"
  [[ -f "${checksum_path}" && ! -L "${checksum_path}" ]] \
    || fail "daily_backup_checksum_not_found"

  checksum_line="$(<"${checksum_path}")"
  read -r stored_sha256 stored_basename trailing_value <<<"${checksum_line}"
  [[ "${stored_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid_daily_backup_checksum"
  [[ "${stored_basename}" == "${backup_basename}" ]] \
    || fail "daily_backup_checksum_name_mismatch"
  [[ -z "${trailing_value:-}" ]] \
    || fail "daily_backup_checksum_has_unexpected_fields"

  backup_stat="$(stat -c '%s|%Y|%y|%i' -- "${backup_path}")"
  actual_sha256="$(
    sha256sum -- "${backup_path}" \
      | awk '{ print $1 }'
  )"
  [[ "${actual_sha256}" == "${stored_sha256}" ]] \
    || fail "daily_backup_checksum_mismatch"
  [[ "$(stat -c '%s|%Y|%y|%i' -- "${backup_path}")" == "${backup_stat}" ]] \
    || fail "daily_backup_changed_during_retention_verification"

  backup_hashes+=("${actual_sha256}")
  backup_stats+=("${backup_stat}")
  verified_backup_count=$((verified_backup_count + 1))

  if [[ "${backup_path}" == "${CURRENT_BACKUP_PATH}" ]]; then
    [[ "${actual_sha256}" == "${CURRENT_BACKUP_SHA256}" ]] \
      || fail "current_backup_sha256_mismatch"
    current_backup_match_count=$((current_backup_match_count + 1))
  fi
done

[[ "${verified_backup_count}" -eq "${backup_count_before}" ]] \
  || fail "daily_backup_verification_count_mismatch"
[[ "${current_backup_match_count}" -eq 1 ]] \
  || fail "current_backup_match_count_mismatch"
[[ "${backup_paths[0]}" == "${CURRENT_BACKUP_PATH}" ]] \
  || fail "current_backup_is_not_newest"

delete_candidate_count=0
if (( backup_count_before > RETENTION_COUNT )); then
  delete_candidate_count=$((backup_count_before - RETENTION_COUNT))
fi

echo "RETENTION_TIMESTAMP_UTC=$(date -u +'%Y%m%dT%H%M%SZ')"
echo "RETENTION_COUNT=${RETENTION_COUNT}"
echo "BACKUP_COUNT_BEFORE=${backup_count_before}"
echo "CHECKSUM_COUNT_BEFORE=${checksum_count_before}"
echo "VERIFIED_BACKUP_COUNT=${verified_backup_count}"
echo "CURRENT_BACKUP_PATH=${CURRENT_BACKUP_PATH}"
echo "CURRENT_BACKUP_SHA256=${CURRENT_BACKUP_SHA256}"
echo "CURRENT_BACKUP_VERIFIED=true"
echo "LEGACY_BACKUP_ROOT=${LEGACY_BACKUP_ROOT}"
echo "LEGACY_DIRECTORY_EXCLUDED=true"
echo "RETENTION_DELETE_CANDIDATE_COUNT=${delete_candidate_count}"

deleted_backup_count=0
for (( array_index=RETENTION_COUNT; array_index<backup_count_before; array_index++ )); do
  delete_path="${backup_paths[${array_index}]}"
  delete_checksum_path="${delete_path}.sha256"

  [[ "$(stat -c '%s|%Y|%y|%i' -- "${delete_path}")" == "${backup_stats[${array_index}]}" ]] \
    || fail "delete_candidate_stat_changed"
  [[ "$(sha256sum -- "${delete_path}" | awk '{ print $1 }')" == "${backup_hashes[${array_index}]}" ]] \
    || fail "delete_candidate_hash_changed"
  [[ -f "${delete_checksum_path}" && ! -L "${delete_checksum_path}" ]] \
    || fail "delete_candidate_checksum_changed"

  echo "RETENTION_DELETE_$((deleted_backup_count + 1))_PATH=${delete_path}"
  echo "RETENTION_DELETE_$((deleted_backup_count + 1))_SHA256=${backup_hashes[${array_index}]}"
  rm -- "${delete_path}" "${delete_checksum_path}"
  [[ ! -e "${delete_path}" && ! -e "${delete_checksum_path}" ]] \
    || fail "retention_delete_incomplete"
  deleted_backup_count=$((deleted_backup_count + 1))
done

[[ "${deleted_backup_count}" -eq "${delete_candidate_count}" ]] \
  || fail "retention_deleted_count_mismatch"
[[ -f "${CURRENT_BACKUP_PATH}" ]] || fail "current_backup_missing_after_retention"
[[ "$(sha256sum -- "${CURRENT_BACKUP_PATH}" | awk '{ print $1 }')" == "${CURRENT_BACKUP_SHA256}" ]] \
  || fail "current_backup_changed_after_retention"

mapfile -d '' -t remaining_backup_paths < <(
  find "${BACKUP_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name 'repoguard-*.sql.gz.enc' \
    -print0 \
    | LC_ALL=C sort -z -r
)
mapfile -d '' -t remaining_checksum_paths < <(
  find "${BACKUP_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name 'repoguard-*.sql.gz.enc.sha256' \
    -print0 \
    | LC_ALL=C sort -z -r
)

backup_count_after="${#remaining_backup_paths[@]}"
checksum_count_after="${#remaining_checksum_paths[@]}"
expected_count_after="${backup_count_before}"
if (( expected_count_after > RETENTION_COUNT )); then
  expected_count_after="${RETENTION_COUNT}"
fi

[[ "${backup_count_after}" -eq "${expected_count_after}" ]] \
  || fail "retention_backup_count_after_mismatch"
[[ "${checksum_count_after}" -eq "${backup_count_after}" ]] \
  || fail "retention_checksum_count_after_mismatch"

echo "RETENTION_DELETED_COUNT=${deleted_backup_count}"
echo "BACKUP_COUNT_AFTER=${backup_count_after}"
echo "CHECKSUM_COUNT_AFTER=${checksum_count_after}"
echo "RETENTION_APPLIED=true"
