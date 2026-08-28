#!/usr/bin/env bash

set -Eeuo pipefail

fail() {
  echo "ERROR=$1" >&2
  exit 1
}

require_value() {
  local variable_name="$1"
  [[ -n "${!variable_name:-}" ]] || fail "missing_${variable_name,,}"
}

validate_endpoint() {
  local endpoint="$1"
  local label="$2"
  [[ -z "${endpoint}" || "${endpoint}" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^?#[:space:]]*)?$ ]] \
    || fail "invalid_${label}_endpoint"
}

validate_target() {
  local bucket="$1"
  local region="$2"
  local kms_key_id="$3"
  local endpoint="$4"
  local label="$5"

  [[ "${bucket}" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] \
    || fail "invalid_${label}_bucket"
  [[ "${region}" =~ ^[a-z0-9][a-z0-9-]{0,30}[a-z0-9]$ ]] \
    || fail "invalid_${label}_region"
  [[ "${kms_key_id}" =~ ^arn:[A-Za-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/[A-Za-z0-9-]+$ ]] \
    || fail "invalid_${label}_kms_key_arn"
  validate_endpoint "${endpoint}" "${label}"
}

aws_for_target() {
  local region="$1"
  local endpoint="$2"
  shift 2

  local arguments=(--no-cli-pager --region "${region}")
  if [[ -n "${endpoint}" ]]; then
    arguments+=(--endpoint-url "${endpoint}")
  fi
  aws "${arguments[@]}" "$@"
}

upload_and_verify() {
  local label="$1"
  local bucket="$2"
  local region="$3"
  local kms_key_id="$4"
  local endpoint="$5"
  local output_file="$6"

  local object_lock
  object_lock="$(aws_for_target "${region}" "${endpoint}" s3api get-object-lock-configuration \
    --bucket "${bucket}" \
    --output json)" || fail "${label}_object_lock_lookup_failed"
  [[ "$(jq -r '.ObjectLockConfiguration.ObjectLockEnabled // empty' <<<"${object_lock}")" == "Enabled" ]] \
    || fail "${label}_object_lock_not_enabled"

  aws_for_target "${region}" "${endpoint}" s3api put-object \
    --bucket "${bucket}" \
    --key "${object_key}" \
    --body "${source_file}" \
    --content-type application/octet-stream \
    --server-side-encryption aws:kms \
    --ssekms-key-id "${kms_key_id}" \
    --checksum-algorithm SHA256 \
    --checksum-sha256 "${checksum_base64}" \
    --object-lock-mode COMPLIANCE \
    --object-lock-retain-until-date "${retain_until}" \
    --metadata "repoguard-source-sha256=${expected_sha256},repoguard-backup-class=${backup_class}" \
    --output json >"${output_file}" \
    || fail "${label}_upload_failed"

  local head
  head="$(aws_for_target "${region}" "${endpoint}" s3api head-object \
    --bucket "${bucket}" \
    --key "${object_key}" \
    --checksum-mode ENABLED \
    --output json)" || fail "${label}_head_verification_failed"

  local stored_retain_until
  local stored_retain_epoch
  local required_retain_epoch
  stored_retain_until="$(jq -er '.ObjectLockRetainUntilDate | strings | select(length > 0)' <<<"${head}")" \
    || fail "${label}_retention_timestamp_missing"
  stored_retain_epoch="$(date -u -d "${stored_retain_until}" +'%s')" \
    || fail "${label}_retention_timestamp_invalid"
  required_retain_epoch="$(date -u -d "${retain_until}" +'%s')" \
    || fail "${label}_required_retention_timestamp_invalid"
  (( stored_retain_epoch >= required_retain_epoch )) \
    || fail "${label}_retention_period_too_short"

  jq -e \
    --arg checksum "${checksum_base64}" \
    --arg sha256 "${expected_sha256}" \
    --arg kms "${kms_key_id}" \
    --argjson bytes "${source_bytes}" \
    '.ContentLength == $bytes
      and .ServerSideEncryption == "aws:kms"
      and .SSEKMSKeyId == $kms
      and .ChecksumSHA256 == $checksum
      and .ObjectLockMode == "COMPLIANCE"
      and .Metadata["repoguard-source-sha256"] == $sha256' \
    <<<"${head}" >/dev/null \
    || fail "${label}_immutable_object_verification_failed"

  printf '%s_OBJECT_URI=s3://%s/%s\n' "${label^^}" "${bucket}" "${object_key}"
  printf '%s_OBJECT_VERIFIED=true\n' "${label^^}"
}

[[ $# -eq 3 ]] \
  || fail "usage_upload_immutable_backup_object_encrypted_file_object_key_sha256"

source_file="$1"
object_key="$2"
expected_sha256="$3"

for command_name in awk aws base64 basename date dirname jq mktemp openssl pwd rm sha256sum stat tr; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "missing_required_command_${command_name}"
done

[[ -f "${source_file}" && ! -L "${source_file}" ]] \
  || fail "source_file_must_be_a_regular_non_symlink"
source_file="$(cd -P -- "$(dirname -- "${source_file}")" && pwd)/$(basename -- "${source_file}")"
source_name="${source_file##*/}"

backup_class=""
case "${source_name}" in
  repoguard-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z.sql.gz.enc)
    backup_class="mysql"
    ;;
  repoguard-binlog-[0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z.tar.gz.enc)
    backup_class="mysql-binlog"
    ;;
  *)
    fail "unsupported_encrypted_backup_name"
    ;;
esac

[[ "${object_key}" =~ ^repoguard/${backup_class}/[0-9]{4}/[0-9]{2}/[0-9]{2}/${source_name}$ ]] \
  || fail "invalid_object_key"
[[ "${expected_sha256}" =~ ^[0-9a-f]{64}$ ]] \
  || fail "invalid_expected_sha256"

actual_sha256="$(sha256sum -- "${source_file}" | awk '{ print $1 }')"
[[ "${actual_sha256}" == "${expected_sha256}" ]] \
  || fail "source_sha256_mismatch"
source_bytes="$(stat -c '%s' -- "${source_file}")"
[[ "${source_bytes}" =~ ^[1-9][0-9]*$ ]] \
  || fail "source_file_is_empty"
checksum_base64="$(openssl dgst -sha256 -binary "${source_file}" | base64 | tr -d '\r\n')"
[[ -n "${checksum_base64}" ]] || fail "checksum_encoding_failed"

require_value REPOGUARD_BACKUP_BUCKET
require_value REPOGUARD_BACKUP_REGION
require_value REPOGUARD_BACKUP_KMS_KEY_ID
require_value REPOGUARD_BACKUP_REPLICA_BUCKET
require_value REPOGUARD_BACKUP_REPLICA_REGION
require_value REPOGUARD_BACKUP_REPLICA_KMS_KEY_ID

primary_endpoint="${REPOGUARD_BACKUP_ENDPOINT:-}"
replica_endpoint="${REPOGUARD_BACKUP_REPLICA_ENDPOINT:-}"
validate_target \
  "${REPOGUARD_BACKUP_BUCKET}" \
  "${REPOGUARD_BACKUP_REGION}" \
  "${REPOGUARD_BACKUP_KMS_KEY_ID}" \
  "${primary_endpoint}" \
  primary
validate_target \
  "${REPOGUARD_BACKUP_REPLICA_BUCKET}" \
  "${REPOGUARD_BACKUP_REPLICA_REGION}" \
  "${REPOGUARD_BACKUP_REPLICA_KMS_KEY_ID}" \
  "${replica_endpoint}" \
  replica
[[ "${REPOGUARD_BACKUP_BUCKET}" != "${REPOGUARD_BACKUP_REPLICA_BUCKET}" \
  || "${REPOGUARD_BACKUP_REGION}" != "${REPOGUARD_BACKUP_REPLICA_REGION}" \
  || "${primary_endpoint}" != "${replica_endpoint}" ]] \
  || fail "primary_and_replica_targets_must_differ"

retention_days="${REPOGUARD_BACKUP_OBJECT_LOCK_DAYS:-30}"
[[ "${retention_days}" =~ ^[0-9]+$ ]] \
  || fail "object_lock_days_must_be_numeric"
(( retention_days >= 30 && retention_days <= 3650 )) \
  || fail "object_lock_days_out_of_range"
retain_until="$(date -u -d "+${retention_days} days" +'%Y-%m-%dT%H:%M:%SZ')"

temporary_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/repoguard-object-upload.XXXXXX")"
cleanup() {
  local exit_code=$?
  set +e
  case "${temporary_dir}" in
    "${RUNNER_TEMP:-/tmp}"/repoguard-object-upload.*)
      rm -rf -- "${temporary_dir}"
      ;;
  esac
  return "${exit_code}"
}
trap cleanup EXIT

upload_and_verify \
  primary \
  "${REPOGUARD_BACKUP_BUCKET}" \
  "${REPOGUARD_BACKUP_REGION}" \
  "${REPOGUARD_BACKUP_KMS_KEY_ID}" \
  "${primary_endpoint}" \
  "${temporary_dir}/primary-put.json"
upload_and_verify \
  replica \
  "${REPOGUARD_BACKUP_REPLICA_BUCKET}" \
  "${REPOGUARD_BACKUP_REPLICA_REGION}" \
  "${REPOGUARD_BACKUP_REPLICA_KMS_KEY_ID}" \
  "${replica_endpoint}" \
  "${temporary_dir}/replica-put.json"

echo "OBJECT_KEY=${object_key}"
echo "OBJECT_SHA256=${expected_sha256}"
echo "OBJECT_BYTES=${source_bytes}"
echo "OBJECT_LOCK_MODE=COMPLIANCE"
echo "OBJECT_LOCK_RETAIN_UNTIL=${retain_until}"
echo "IMMUTABLE_REPLICATION_VERIFIED=true"
