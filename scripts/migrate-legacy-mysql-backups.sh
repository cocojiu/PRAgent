#!/usr/bin/env bash

set -Eeuo pipefail

MODE="${MODE:-inventory}"
SOURCE_ROOT="/opt/repoguard/backups"
DESTINATION_ROOT="/opt/repoguard/backups/mysql/legacy"

declare -a partial_files=()
declare -a created_files=()

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
  local file_path
  set +e

  for file_path in "${partial_files[@]:-}"; do
    case "${file_path}" in
      /opt/repoguard/backups/mysql/legacy/.*.partial.*)
        [[ -f "${file_path}" ]] && rm -f -- "${file_path}"
        ;;
    esac
  done

  if (( exit_code != 0 )); then
    for file_path in "${created_files[@]:-}"; do
      case "${file_path}" in
        /opt/repoguard/backups/mysql/legacy/*)
          [[ -f "${file_path}" ]] && rm -f -- "${file_path}"
          ;;
      esac
    done
  fi

  unset BACKUP_ENCRYPTION_PASSWORD
  return "${exit_code}"
}
trap 'report_unexpected_error "${LINENO}"' ERR
trap cleanup EXIT

for command_name in awk chmod date df find gzip mkdir mv openssl rm sha256sum sort stat wc; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "missing_required_command_${command_name}"
done

case "${MODE}" in
  inventory)
    [[ $# -eq 0 ]] || fail "inventory_mode_accepts_no_arguments"
    ;;
  encrypt)
    [[ "${1:-}" == "--password-stdin" && $# -eq 1 ]] \
      || fail "encryption_password_must_be_supplied_via_stdin"
    IFS= read -r BACKUP_ENCRYPTION_PASSWORD
    export BACKUP_ENCRYPTION_PASSWORD
    [[ ${#BACKUP_ENCRYPTION_PASSWORD} -ge 32 ]] \
      || fail "backup_encryption_password_too_short"
    ;;
  *)
    fail "mode_must_be_inventory_or_encrypt"
    ;;
esac

[[ -d "${SOURCE_ROOT}" ]] || fail "source_root_not_found"
SOURCE_ROOT="$(cd -P -- "${SOURCE_ROOT}" && pwd)"
[[ "${SOURCE_ROOT}" == "/opt/repoguard/backups" ]] \
  || fail "source_root_outside_allowed_path"

if [[ "${MODE}" == "encrypt" ]]; then
  umask 077
  [[ -d "/opt/repoguard/backups/mysql" ]] \
    || fail "encrypted_backup_parent_not_found"
  encrypted_backup_parent="$(
    cd -P -- "/opt/repoguard/backups/mysql" \
      && pwd
  )"
  [[ "${encrypted_backup_parent}" == "/opt/repoguard/backups/mysql" ]] \
    || fail "encrypted_backup_parent_outside_allowed_path"
  mkdir -p -- "${DESTINATION_ROOT}"
  chmod 0700 -- "${DESTINATION_ROOT}"
  DESTINATION_ROOT="$(cd -P -- "${DESTINATION_ROOT}" && pwd)"
  [[ "${DESTINATION_ROOT}" == "/opt/repoguard/backups/mysql/legacy" ]] \
    || fail "destination_root_outside_allowed_path"
fi

mapfile -d '' -t source_files < <(
  find "${SOURCE_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name '*.sql' \
    -print0 \
    | LC_ALL=C sort -z
)

legacy_backup_count="${#source_files[@]}"
total_source_bytes=0
for source_path in "${source_files[@]}"; do
  source_basename="${source_path##*/}"
  [[ "${source_path}" == "${SOURCE_ROOT}/${source_basename}" ]] \
    || fail "legacy_backup_not_direct_child"
  [[ "${source_basename}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.sql$ ]] \
    || fail "unsupported_legacy_backup_filename"
  [[ ! -L "${source_path}" ]] || fail "legacy_backup_symlink_not_allowed"

  source_bytes="$(stat -c '%s' -- "${source_path}")"
  [[ "${source_bytes}" =~ ^[0-9]+$ ]] || fail "invalid_legacy_backup_size"
  total_source_bytes=$((total_source_bytes + source_bytes))
done

if [[ "${MODE}" == "encrypt" && "${legacy_backup_count}" -gt 0 ]]; then
  available_kb="$(df -Pk "${DESTINATION_ROOT}" | awk 'NR == 2 { print $4 }')"
  [[ "${available_kb}" =~ ^[0-9]+$ ]] || fail "invalid_available_disk_space"
  required_kb=$(( (total_source_bytes / 1024) + 65536 ))
  (( available_kb >= required_kb )) || fail "insufficient_backup_disk_space"
fi

inventory_timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
echo "OPERATION=${MODE}"
echo "INVENTORY_TIMESTAMP_UTC=${inventory_timestamp}"
echo "LEGACY_BACKUP_COUNT=${legacy_backup_count}"
echo "TOTAL_SOURCE_BYTES=${total_source_bytes}"

encrypted_backup_count=0
roundtrip_verified_count=0
file_index=0

for source_path in "${source_files[@]}"; do
  file_index=$((file_index + 1))
  source_basename="${source_path##*/}"
  source_stat_before="$(stat -c '%s|%Y|%y|%i' -- "${source_path}")"
  IFS='|' read -r \
    source_bytes \
    source_mtime_epoch \
    source_mtime_detail \
    source_inode \
    <<<"${source_stat_before}"
  source_sha256="$(sha256sum -- "${source_path}" | awk '{ print $1 }')"
  source_stat_after="$(stat -c '%s|%Y|%y|%i' -- "${source_path}")"
  [[ "${source_stat_after}" == "${source_stat_before}" ]] \
    || fail "legacy_backup_changed_during_inventory"
  [[ "${source_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid_legacy_backup_sha256"
  source_mtime_utc="$(
    date -u --date="@${source_mtime_epoch}" +'%Y%m%dT%H%M%SZ'
  )"

  echo "LEGACY_${file_index}_SOURCE_PATH=${source_path}"
  echo "LEGACY_${file_index}_SOURCE_BYTES=${source_bytes}"
  echo "LEGACY_${file_index}_SOURCE_MTIME_UTC=${source_mtime_utc}"
  echo "LEGACY_${file_index}_SOURCE_SHA256=${source_sha256}"

  if [[ "${MODE}" != "encrypt" ]]; then
    continue
  fi

  encrypted_basename="${source_basename}.gz.enc"
  encrypted_path="${DESTINATION_ROOT}/${encrypted_basename}"
  encrypted_checksum_path="${encrypted_path}.sha256"
  source_checksum_path="${encrypted_path}.source.sha256"

  for candidate_path in \
    "${encrypted_path}" \
    "${encrypted_checksum_path}" \
    "${source_checksum_path}"; do
    [[ ! -L "${candidate_path}" ]] \
      || fail "legacy_encrypted_backup_symlink_not_allowed"
  done

  migration_status="created"
  if [[ -e "${encrypted_path}" \
    || -e "${encrypted_checksum_path}" \
    || -e "${source_checksum_path}" ]]; then
    [[ -f "${encrypted_path}" \
      && -f "${encrypted_checksum_path}" \
      && -f "${source_checksum_path}" ]] \
      || fail "incomplete_existing_legacy_migration"

    stored_encrypted_sha256="$(<"${encrypted_checksum_path}")"
    stored_source_sha256="$(<"${source_checksum_path}")"
    [[ "${stored_encrypted_sha256}" =~ ^[0-9a-f]{64}$ ]] \
      || fail "invalid_existing_encrypted_checksum"
    [[ "${stored_source_sha256}" =~ ^[0-9a-f]{64}$ ]] \
      || fail "invalid_existing_source_checksum"
    [[ "${stored_source_sha256}" == "${source_sha256}" ]] \
      || fail "existing_encrypted_backup_source_mismatch"

    encrypted_sha256="$(sha256sum -- "${encrypted_path}" | awk '{ print $1 }')"
    [[ "${encrypted_sha256}" == "${stored_encrypted_sha256}" ]] \
      || fail "existing_encrypted_backup_checksum_mismatch"
    chmod 0600 -- \
      "${encrypted_path}" \
      "${encrypted_checksum_path}" \
      "${source_checksum_path}"
    migration_status="verified_existing"
  else
    encrypted_partial="${DESTINATION_ROOT}/.${encrypted_basename}.partial.$$"
    encrypted_checksum_partial="${encrypted_partial}.sha256"
    source_checksum_partial="${encrypted_partial}.source.sha256"
    partial_files+=(
      "${encrypted_partial}"
      "${encrypted_checksum_partial}"
      "${source_checksum_partial}"
    )

    gzip -9 -c -- "${source_path}" \
      | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -md sha256 \
          -pass env:BACKUP_ENCRYPTION_PASSWORD \
          -out "${encrypted_partial}"
    [[ -s "${encrypted_partial}" ]] || fail "encrypted_legacy_backup_is_empty"

    encrypted_sha256="$(sha256sum -- "${encrypted_partial}" | awk '{ print $1 }')"
    printf '%s\n' "${encrypted_sha256}" >"${encrypted_checksum_partial}"
    printf '%s\n' "${source_sha256}" >"${source_checksum_partial}"
    chmod 0600 -- \
      "${encrypted_partial}" \
      "${encrypted_checksum_partial}" \
      "${source_checksum_partial}"

    roundtrip_sha256="$(
      openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
        -pass env:BACKUP_ENCRYPTION_PASSWORD \
        -in "${encrypted_partial}" \
        | gzip -dc \
        | sha256sum \
        | awk '{ print $1 }'
    )"
    [[ "${roundtrip_sha256}" == "${source_sha256}" ]] \
      || fail "encrypted_legacy_backup_roundtrip_mismatch"
    [[ "$(stat -c '%s|%Y|%y|%i' -- "${source_path}")" == "${source_stat_before}" ]] \
      || fail "legacy_backup_changed_during_encryption"
    [[ "$(sha256sum -- "${source_path}" | awk '{ print $1 }')" == "${source_sha256}" ]] \
      || fail "legacy_backup_checksum_changed_during_encryption"

    created_files+=(
      "${encrypted_path}"
      "${encrypted_checksum_path}"
      "${source_checksum_path}"
    )
    mv -- "${encrypted_partial}" "${encrypted_path}"
    mv -- "${encrypted_checksum_partial}" "${encrypted_checksum_path}"
    mv -- "${source_checksum_partial}" "${source_checksum_path}"
  fi

  stored_encrypted_sha256="$(<"${encrypted_checksum_path}")"
  stored_source_sha256="$(<"${source_checksum_path}")"
  [[ "${stored_source_sha256}" == "${source_sha256}" ]] \
    || fail "final_source_checksum_mismatch"
  [[ "$(sha256sum -- "${encrypted_path}" | awk '{ print $1 }')" == "${stored_encrypted_sha256}" ]] \
    || fail "final_encrypted_checksum_mismatch"

  roundtrip_sha256="$(
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
      -pass env:BACKUP_ENCRYPTION_PASSWORD \
      -in "${encrypted_path}" \
      | gzip -dc \
      | sha256sum \
      | awk '{ print $1 }'
  )"
  [[ "${roundtrip_sha256}" == "${source_sha256}" ]] \
    || fail "final_encrypted_legacy_backup_roundtrip_mismatch"
  [[ "$(stat -c '%s|%Y|%y|%i' -- "${source_path}")" == "${source_stat_before}" ]] \
    || fail "legacy_backup_changed_before_completion"
  [[ "$(sha256sum -- "${source_path}" | awk '{ print $1 }')" == "${source_sha256}" ]] \
    || fail "legacy_backup_checksum_changed_before_completion"

  encrypted_bytes="$(stat -c '%s' -- "${encrypted_path}")"
  encrypted_backup_count=$((encrypted_backup_count + 1))
  roundtrip_verified_count=$((roundtrip_verified_count + 1))

  echo "LEGACY_${file_index}_ENCRYPTED_PATH=${encrypted_path}"
  echo "LEGACY_${file_index}_ENCRYPTED_BYTES=${encrypted_bytes}"
  echo "LEGACY_${file_index}_ENCRYPTED_SHA256=${stored_encrypted_sha256}"
  echo "LEGACY_${file_index}_MIGRATION_STATUS=${migration_status}"
  echo "LEGACY_${file_index}_ROUNDTRIP_VERIFIED=true"
done

if [[ "${MODE}" == "encrypt" ]]; then
  key_fingerprint="$(
    printf '%s' "${BACKUP_ENCRYPTION_PASSWORD}" \
      | sha256sum \
      | awk '{ print $1 }'
  )"
  echo "KEY_FINGERPRINT=${key_fingerprint}"
fi

echo "ENCRYPTED_BACKUP_COUNT=${encrypted_backup_count}"
echo "ROUNDTRIP_VERIFIED_COUNT=${roundtrip_verified_count}"
echo "PLAINTEXT_DELETED=false"
