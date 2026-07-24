#!/usr/bin/env bash

set -Eeuo pipefail

SOURCE_ROOT="/opt/repoguard/backups"
DESTINATION_ROOT="/opt/repoguard/backups/mysql/legacy"
LOCK_DIRECTORY="/opt/repoguard/backups/.legacy-plaintext-delete.lock"
CONFIRMATION_TOKEN="DELETE_4_VERIFIED_PLAINTEXT_SQL_FILES"
CONFIRMED_MANIFEST_SHA256="a14872adbd19c7d9e37ae58aa04c2704e693161d74ad14c7c9f30a0d1936e278"
EXPECTED_BACKUP_COUNT=4
EXPECTED_EMPTY_COUNT=2
EXPECTED_NONEMPTY_COUNT=2

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
      /opt/repoguard/backups/.legacy-plaintext-delete.lock)
        rmdir -- "${LOCK_DIRECTORY}" >/dev/null 2>&1
        ;;
    esac
  fi

  unset BACKUP_ENCRYPTION_PASSWORD
  return "${exit_code}"
}
trap 'report_unexpected_error "${LINENO}"' ERR
trap cleanup EXIT

for command_name in awk chmod date find gzip mkdir openssl rm rmdir sha256sum sort stat; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "missing_required_command_${command_name}"
done

[[ "${1:-}" == "--password-stdin" && $# -eq 1 ]] \
  || fail "encryption_password_must_be_supplied_via_stdin"
[[ "${DELETE_CONFIRMATION:-}" == "${CONFIRMATION_TOKEN}" ]] \
  || fail "delete_confirmation_token_mismatch"

IFS= read -r BACKUP_ENCRYPTION_PASSWORD
export BACKUP_ENCRYPTION_PASSWORD
[[ ${#BACKUP_ENCRYPTION_PASSWORD} -ge 32 ]] \
  || fail "backup_encryption_password_too_short"

[[ -d "${SOURCE_ROOT}" ]] || fail "source_root_not_found"
SOURCE_ROOT="$(cd -P -- "${SOURCE_ROOT}" && pwd)"
[[ "${SOURCE_ROOT}" == "/opt/repoguard/backups" ]] \
  || fail "source_root_outside_allowed_path"

[[ -d "${DESTINATION_ROOT}" ]] || fail "encrypted_backup_root_not_found"
DESTINATION_ROOT="$(cd -P -- "${DESTINATION_ROOT}" && pwd)"
[[ "${DESTINATION_ROOT}" == "/opt/repoguard/backups/mysql/legacy" ]] \
  || fail "encrypted_backup_root_outside_allowed_path"

umask 077
mkdir -- "${LOCK_DIRECTORY}" \
  || fail "legacy_plaintext_delete_lock_unavailable"
lock_acquired="true"
chmod 0700 -- "${LOCK_DIRECTORY}"

mapfile -d '' -t source_files < <(
  find "${SOURCE_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name '*.sql' \
    -print0 \
    | LC_ALL=C sort -z
)

legacy_backup_count="${#source_files[@]}"
[[ "${legacy_backup_count}" -eq "${EXPECTED_BACKUP_COUNT}" ]] \
  || fail "confirmed_legacy_backup_count_mismatch"

declare -a manifest_lines=()
declare -a source_stats=()
declare -a source_hashes=()

empty_backup_count=0
nonempty_backup_count=0
roundtrip_verified_count=0
total_source_bytes=0
file_index=0

echo "OPERATION=delete"
echo "VERIFICATION_TIMESTAMP_UTC=$(date -u +'%Y%m%dT%H%M%SZ')"
echo "LEGACY_BACKUP_COUNT_BEFORE=${legacy_backup_count}"

for source_path in "${source_files[@]}"; do
  file_index=$((file_index + 1))
  source_basename="${source_path##*/}"

  [[ "${source_path}" == "${SOURCE_ROOT}/${source_basename}" ]] \
    || fail "legacy_backup_not_direct_child"
  [[ "${source_basename}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.sql$ ]] \
    || fail "unsupported_legacy_backup_filename"
  [[ ! -L "${source_path}" ]] || fail "legacy_backup_symlink_not_allowed"

  source_stat_before="$(stat -c '%s|%Y|%y|%i' -- "${source_path}")"
  IFS='|' read -r \
    source_bytes \
    source_mtime_epoch \
    source_mtime_detail \
    source_inode \
    <<<"${source_stat_before}"
  [[ "${source_bytes}" =~ ^[0-9]+$ ]] \
    || fail "invalid_legacy_backup_size"

  source_sha256="$(sha256sum -- "${source_path}" | awk '{ print $1 }')"
  [[ "${source_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid_legacy_backup_sha256"
  [[ "$(stat -c '%s|%Y|%y|%i' -- "${source_path}")" == "${source_stat_before}" ]] \
    || fail "legacy_backup_changed_during_delete_verification"

  source_mtime_utc="$(
    date -u --date="@${source_mtime_epoch}" +'%Y%m%dT%H%M%SZ'
  )"
  total_source_bytes=$((total_source_bytes + source_bytes))
  manifest_lines+=(
    "${source_basename}"$'\t'"${source_bytes}"$'\t'"${source_sha256}"
  )
  source_stats+=("${source_stat_before}")
  source_hashes+=("${source_sha256}")

  echo "LEGACY_${file_index}_SOURCE_PATH=${source_path}"
  echo "LEGACY_${file_index}_SOURCE_BYTES=${source_bytes}"
  echo "LEGACY_${file_index}_SOURCE_MTIME_UTC=${source_mtime_utc}"
  echo "LEGACY_${file_index}_SOURCE_SHA256=${source_sha256}"

  if (( source_bytes == 0 )); then
    [[ "${source_sha256}" == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ]] \
      || fail "empty_legacy_backup_hash_mismatch"
    empty_backup_count=$((empty_backup_count + 1))
    echo "LEGACY_${file_index}_CLASSIFICATION=empty"
    echo "LEGACY_${file_index}_ENCRYPTED_COPY_VERIFIED=not_required"
    continue
  fi

  nonempty_backup_count=$((nonempty_backup_count + 1))
  encrypted_basename="${source_basename}.gz.enc"
  encrypted_path="${DESTINATION_ROOT}/${encrypted_basename}"
  encrypted_checksum_path="${encrypted_path}.sha256"
  source_checksum_path="${encrypted_path}.source.sha256"

  for candidate_path in \
    "${encrypted_path}" \
    "${encrypted_checksum_path}" \
    "${source_checksum_path}"; do
    [[ -f "${candidate_path}" && ! -L "${candidate_path}" ]] \
      || fail "verified_encrypted_copy_not_found"
  done

  stored_encrypted_sha256="$(<"${encrypted_checksum_path}")"
  stored_source_sha256="$(<"${source_checksum_path}")"
  [[ "${stored_encrypted_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid_encrypted_checksum"
  [[ "${stored_source_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid_source_checksum"
  [[ "${stored_source_sha256}" == "${source_sha256}" ]] \
    || fail "encrypted_copy_source_checksum_mismatch"

  actual_encrypted_sha256="$(
    sha256sum -- "${encrypted_path}" \
      | awk '{ print $1 }'
  )"
  [[ "${actual_encrypted_sha256}" == "${stored_encrypted_sha256}" ]] \
    || fail "encrypted_copy_checksum_mismatch"

  roundtrip_sha256="$(
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
      -pass env:BACKUP_ENCRYPTION_PASSWORD \
      -in "${encrypted_path}" \
      | gzip -dc \
      | sha256sum \
      | awk '{ print $1 }'
  )"
  [[ "${roundtrip_sha256}" == "${source_sha256}" ]] \
    || fail "encrypted_copy_roundtrip_mismatch"
  [[ "$(stat -c '%s|%Y|%y|%i' -- "${source_path}")" == "${source_stat_before}" ]] \
    || fail "legacy_backup_changed_during_encrypted_copy_verification"
  [[ "$(sha256sum -- "${source_path}" | awk '{ print $1 }')" == "${source_sha256}" ]] \
    || fail "legacy_backup_hash_changed_during_encrypted_copy_verification"

  roundtrip_verified_count=$((roundtrip_verified_count + 1))
  echo "LEGACY_${file_index}_CLASSIFICATION=nonempty"
  echo "LEGACY_${file_index}_ENCRYPTED_PATH=${encrypted_path}"
  echo "LEGACY_${file_index}_ENCRYPTED_SHA256=${actual_encrypted_sha256}"
  echo "LEGACY_${file_index}_ENCRYPTED_COPY_VERIFIED=true"
done

[[ "${empty_backup_count}" -eq "${EXPECTED_EMPTY_COUNT}" ]] \
  || fail "confirmed_empty_backup_count_mismatch"
[[ "${nonempty_backup_count}" -eq "${EXPECTED_NONEMPTY_COUNT}" ]] \
  || fail "confirmed_nonempty_backup_count_mismatch"
[[ "${roundtrip_verified_count}" -eq "${EXPECTED_NONEMPTY_COUNT}" ]] \
  || fail "encrypted_copy_verification_count_mismatch"

manifest_sha256="$(
  printf '%s\n' "${manifest_lines[@]}" \
    | sha256sum \
    | awk '{ print $1 }'
)"
[[ "${manifest_sha256}" == "${CONFIRMED_MANIFEST_SHA256}" ]] \
  || fail "confirmed_manifest_sha256_mismatch"

mapfile -d '' -t final_source_files < <(
  find "${SOURCE_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name '*.sql' \
    -print0 \
    | LC_ALL=C sort -z
)
[[ "${#final_source_files[@]}" -eq "${legacy_backup_count}" ]] \
  || fail "legacy_backup_set_changed_before_delete"

for array_index in "${!source_files[@]}"; do
  [[ "${final_source_files[${array_index}]}" == "${source_files[${array_index}]}" ]] \
    || fail "legacy_backup_path_changed_before_delete"
  [[ "$(stat -c '%s|%Y|%y|%i' -- "${source_files[${array_index}]}")" == "${source_stats[${array_index}]}" ]] \
    || fail "legacy_backup_stat_changed_before_delete"
  [[ "$(sha256sum -- "${source_files[${array_index}]}" | awk '{ print $1 }')" == "${source_hashes[${array_index}]}" ]] \
    || fail "legacy_backup_hash_changed_before_delete"
done

echo "EMPTY_BACKUP_COUNT=${empty_backup_count}"
echo "NONEMPTY_BACKUP_COUNT=${nonempty_backup_count}"
echo "TOTAL_SOURCE_BYTES=${total_source_bytes}"
echo "CONFIRMED_MANIFEST_SHA256=${manifest_sha256}"
echo "ROUNDTRIP_VERIFIED_COUNT=${roundtrip_verified_count}"
echo "PREDELETE_VERIFIED=true"

rm -- "${source_files[@]}"

deleted_backup_count=0
for source_path in "${source_files[@]}"; do
  [[ ! -e "${source_path}" ]] || fail "legacy_plaintext_delete_incomplete"
  deleted_backup_count=$((deleted_backup_count + 1))
done

mapfile -d '' -t remaining_source_files < <(
  find "${SOURCE_ROOT}" \
    -maxdepth 1 \
    -type f \
    -name '*.sql' \
    -print0 \
    | LC_ALL=C sort -z
)
remaining_backup_count="${#remaining_source_files[@]}"
[[ "${remaining_backup_count}" -eq 0 ]] \
  || fail "legacy_plaintext_backups_remain_after_delete"

echo "DELETION_TIMESTAMP_UTC=$(date -u +'%Y%m%dT%H%M%SZ')"
echo "DELETED_BACKUP_COUNT=${deleted_backup_count}"
echo "REMAINING_PLAINTEXT_BACKUP_COUNT=${remaining_backup_count}"
echo "PLAINTEXT_DELETED=true"
