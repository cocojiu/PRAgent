#!/usr/bin/env sh
set -eu
umask 077

MODE="${1:-prepare}"
ENV_FILE="${ENV_FILE:-.env}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-.deploy-state}"
INITIALIZE_MISSING_ENCRYPTION_SALT="${INITIALIZE_MISSING_ENCRYPTION_SALT:-false}"

case "$MODE" in
  prepare|finalize) ;;
  *)
    echo "Usage: $0 prepare|finalize" >&2
    exit 1
    ;;
esac

case "$INITIALIZE_MISSING_ENCRYPTION_SALT" in
  true|false) ;;
  *)
    echo "INITIALIZE_MISSING_ENCRYPTION_SALT must be true or false." >&2
    exit 1
    ;;
esac

for required_command in awk basename cat chmod cmp cp date dirname docker grep install mktemp mv od rm sed stat tail tr; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Missing required secret migration command: $required_command" >&2
    exit 1
  fi
done

if [ "$INITIALIZE_MISSING_ENCRYPTION_SALT" = "true" ] \
  && ! command -v openssl >/dev/null 2>&1; then
  echo "Missing required command for encryption salt initialization: openssl" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ] || [ ! -r "$ENV_FILE" ] || [ -L "$ENV_FILE" ]; then
  echo "ENV_FILE must be a readable regular file, not a symlink: $ENV_FILE" >&2
  exit 1
fi

env_directory="$(CDPATH= cd "$(dirname "$ENV_FILE")" && pwd)"
env_path="${env_directory}/$(basename "$ENV_FILE")"
work_directory="$(mktemp -d "${env_directory}/.secret-file-migration.XXXXXX")"
chmod 700 "$work_directory"
mapping_file="${work_directory}/mappings"
parse_compose_file="${work_directory}/compose.yml"
parsed_environment_file="${work_directory}/environment"
reference_file="${work_directory}/references"
move_plan_file="${work_directory}/move-plan"
state_directory="$DEPLOY_STATE_DIR"
case "$state_directory" in
  /*) ;;
  *) state_directory="$(pwd)/${state_directory#./}" ;;
esac
state_marker="${state_directory}/secret-file-migration.pending"

cleanup() {
  case "${work_directory:-}" in
    "$env_directory"/.secret-file-migration.*)
      if [ -d "$work_directory" ] && [ ! -L "$work_directory" ]; then
        rm -rf "$work_directory"
      fi
      ;;
  esac
}
trap cleanup 0 1 2 3 15

cat > "$mapping_file" <<'EOF'
MYSQL_ROOT_PASSWORD|MYSQL_ROOT_PASSWORD_FILE|./secrets/mysql.root-password
MYSQL_PASSWORD|MYSQL_PASSWORD_FILE|./secrets/spring.datasource.password
REPOGUARD_SECURITY_ENCRYPTION_KEY|REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE|./secrets/repoguard.security.encryption-key
REPOGUARD_SECURITY_ENCRYPTION_SALT|REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE|./secrets/repoguard.security.encryption-salt
REPOGUARD_AUTH_TOKEN_SECRET|REPOGUARD_AUTH_TOKEN_SECRET_FILE|./secrets/repoguard.auth.token-secret
REPOGUARD_ADMIN_API_KEY|REPOGUARD_ADMIN_API_KEY_FILE|./secrets/app.security.admin-api-key.key
REPOGUARD_GITHUB_WEBHOOK_SECRET|REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE|./secrets/app.github.webhook.secret
EOF

# The migration must preserve the values stored in ENV_FILE. Explicitly clear
# every target override before Compose reads the file; keeping this outside a
# redirected compound command also makes the behavior portable across /bin/sh
# implementations that may execute such commands in a subshell.
unset MYSQL_ROOT_PASSWORD MYSQL_ROOT_PASSWORD_FILE
unset MYSQL_PASSWORD MYSQL_PASSWORD_FILE
unset REPOGUARD_SECURITY_ENCRYPTION_KEY REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE
unset REPOGUARD_SECURITY_ENCRYPTION_SALT REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE
unset REPOGUARD_AUTH_TOKEN_SECRET REPOGUARD_AUTH_TOKEN_SECRET_FILE
unset REPOGUARD_ADMIN_API_KEY REPOGUARD_ADMIN_API_KEY_FILE
unset REPOGUARD_GITHUB_WEBHOOK_SECRET REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE

{
  echo "services:"
  echo "  secret-migration-environment:"
  echo "    image: scratch"
  echo "    environment:"
  while IFS='|' read -r legacy_key file_key default_reference; do
    printf '      %s: ${%s-}\n' "$legacy_key" "$legacy_key"
    printf '      %s: ${%s-}\n' "$file_key" "$file_key"
  done < "$mapping_file"
} > "$parse_compose_file"

docker compose --env-file "$env_path" -f "$parse_compose_file" \
  config --environment > "$parsed_environment_file"
chmod 600 "$parsed_environment_file"

parsed_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$parsed_environment_file" | tail -n 1
}

parsed_key_exists() {
  key="$1"
  grep -q "^${key}=" "$parsed_environment_file"
}

resolve_secret_path() {
  reference="$1"
  case "$reference" in
    ""|*[!A-Za-z0-9_./-]*|../*|*/../*|*/..)
      echo "Unsafe secret file reference: $reference" >&2
      return 1
      ;;
    /*)
      printf '%s\n' "$reference"
      ;;
    *)
      printf '%s/%s\n' "$env_directory" "${reference#./}"
      ;;
  esac
}

validate_secret_file() {
  validation_setting="$1"
  validation_secret_path="$2"
  if [ -L "$validation_secret_path" ] || [ ! -f "$validation_secret_path" ] \
    || [ ! -r "$validation_secret_path" ] || [ ! -s "$validation_secret_path" ]; then
    echo "$validation_setting must reference a readable, non-empty regular file, not a symlink: $validation_secret_path" >&2
    return 1
  fi

  validation_file_mode="$(stat -c '%a' "$validation_secret_path")"
  case "$validation_file_mode" in
    400|600) ;;
    *)
      echo "$validation_setting must use mode 0400 or 0600; found $validation_file_mode on $validation_secret_path" >&2
      return 1
      ;;
  esac

  validation_directory_mode="$(stat -c '%a' "$(dirname "$validation_secret_path")")"
  case "$validation_directory_mode" in
    500|700) ;;
    *)
      echo "Secret directory must use mode 0500 or 0700; found $validation_directory_mode for $(dirname "$validation_secret_path")" >&2
      return 1
      ;;
  esac

  validation_last_byte="$(
    tail -c 1 "$validation_secret_path" | od -An -t u1 | tr -d ' \n'
  )"
  case "$validation_last_byte" in
    10|13)
      echo "$validation_setting contains a trailing newline: $validation_secret_path" >&2
      return 1
      ;;
  esac
}

ensure_private_directory() {
  directory="$1"
  if [ -L "$directory" ] || { [ -e "$directory" ] && [ ! -d "$directory" ]; }; then
    echo "Private directory must be a directory, not a symlink: $directory" >&2
    return 1
  fi
  install -d -m 700 "$directory"
}

create_env_backup() {
  backup_root="${env_directory}/.secret-migration-backups"
  ensure_private_directory "$backup_root"
  backup_directory="${backup_root}/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  install -d -m 700 "$backup_directory"
  cp -p "$env_path" "${backup_directory}/env.before"
  chmod 600 "${backup_directory}/env.before"
  printf '%s\n' "$backup_directory"
}

rewrite_env() {
  keep_legacy="$1"
  backup_directory="$2"
  env_candidate="${work_directory}/env.updated"

  if [ "$keep_legacy" = "true" ]; then
    awk '
      !/^[[:space:]]*(export[[:space:]]+)?(MYSQL_ROOT_PASSWORD_FILE|MYSQL_PASSWORD_FILE|REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE|REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE|REPOGUARD_AUTH_TOKEN_SECRET_FILE|REPOGUARD_ADMIN_API_KEY_FILE|REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE)[[:space:]]*=/ &&
      !/^[[:space:]]*#[[:space:]]*RepoGuard production secret files[[:space:]]*$/
    ' "$env_path" > "$env_candidate"
  else
    awk '
      !/^[[:space:]]*(export[[:space:]]+)?(MYSQL_ROOT_PASSWORD|MYSQL_PASSWORD|REPOGUARD_SECURITY_ENCRYPTION_KEY|REPOGUARD_SECURITY_ENCRYPTION_SALT|REPOGUARD_AUTH_TOKEN_SECRET|REPOGUARD_ADMIN_API_KEY|REPOGUARD_GITHUB_WEBHOOK_SECRET|MYSQL_ROOT_PASSWORD_FILE|MYSQL_PASSWORD_FILE|REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE|REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE|REPOGUARD_AUTH_TOKEN_SECRET_FILE|REPOGUARD_ADMIN_API_KEY_FILE|REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE)[[:space:]]*=/ &&
      !/^[[:space:]]*#[[:space:]]*RepoGuard production secret files[[:space:]]*$/
    ' "$env_path" > "$env_candidate"
  fi

  {
    printf '\n# RepoGuard production secret files\n'
    cat "$reference_file"
  } >> "$env_candidate"
  chmod 600 "$env_candidate"
  mv "$env_candidate" "$env_path"

  ensure_private_directory "$state_directory"
  if [ "$keep_legacy" = "true" ]; then
    marker_candidate="${work_directory}/migration.pending"
    printf 'backup_directory=%s\n' "$backup_directory" > "$marker_candidate"
    chmod 600 "$marker_candidate"
    mv "$marker_candidate" "$state_marker"
  else
    rm -f "$state_marker"
  fi
}

migration_changes_present=false
: > "$reference_file"
: > "$move_plan_file"

while IFS='|' read -r legacy_key file_key default_reference; do
  legacy_value=""
  legacy_value_is_present=false
  generate_missing_salt=false
  if parsed_key_exists "$legacy_key"; then
    legacy_value="$(parsed_value "$legacy_key")"
    legacy_value_is_present=true
  fi

  file_reference=""
  reference_was_missing=false
  if parsed_key_exists "$file_key"; then
    file_reference="$(parsed_value "$file_key")"
  else
    file_reference="$default_reference"
    reference_was_missing=true
  fi

  secret_path="$(resolve_secret_path "$file_reference")"
  printf '%s=%s\n' "$file_key" "$file_reference" >> "$reference_file"

  if [ "$legacy_value_is_present" = "false" ]; then
    if [ "$reference_was_missing" = "true" ]; then
      if [ "$legacy_key" != "REPOGUARD_SECURITY_ENCRYPTION_SALT" ] \
        || [ "$INITIALIZE_MISSING_ENCRYPTION_SALT" != "true" ]; then
        echo "Neither $legacy_key nor $file_key is configured." >&2
        exit 1
      fi
      migration_changes_present=true
      if [ -e "$secret_path" ] || [ -L "$secret_path" ]; then
        validate_secret_file "$file_key" "$secret_path"
        continue
      fi
      generate_missing_salt=true
    else
      validate_secret_file "$file_key" "$secret_path"
      continue
    fi
  fi

  migration_changes_present=true
  candidate="${work_directory}/${file_key}"
  if [ "$generate_missing_salt" = "true" ]; then
    openssl rand -hex 32 > "$candidate"
    # openssl terminates text output with LF; secret files must not.
    salt_without_newline="${work_directory}/generated-encryption-salt"
    tr -d '\r\n' < "$candidate" > "$salt_without_newline"
    mv "$salt_without_newline" "$candidate"
  else
    printf '%s' "$legacy_value" > "$candidate"
  fi
  chmod 600 "$candidate"
  validate_secret_file "$file_key" "$candidate"

  if [ -e "$secret_path" ] || [ -L "$secret_path" ]; then
    validate_secret_file "$file_key" "$secret_path"
    if ! cmp -s "$candidate" "$secret_path"; then
      echo "Existing secret file does not match the active legacy value: $file_key" >&2
      exit 1
    fi
    rm -f "$candidate"
    continue
  fi

  if [ "$reference_was_missing" != "true" ]; then
    echo "Configured secret file does not exist; refusing to create a custom path: $file_reference" >&2
    exit 1
  fi
  if [ "$MODE" = "finalize" ]; then
    echo "Secret file disappeared before migration finalization: $file_key" >&2
    exit 1
  fi
  ensure_private_directory "$(dirname "$secret_path")"
  printf '%s|%s|%s\n' "$candidate" "$secret_path" "$file_key" >> "$move_plan_file"
done < "$mapping_file"

if [ "$migration_changes_present" = "false" ]; then
  rm -f "$state_marker"
  echo "Production secret-file migration is already complete."
  exit 0
fi

if [ "$MODE" = "prepare" ]; then
  backup_directory="$(create_env_backup)"
  while IFS='|' read -r candidate secret_path file_key; do
    mv "$candidate" "$secret_path"
    chmod 600 "$secret_path"
    validate_secret_file "$file_key" "$secret_path"
  done < "$move_plan_file"
  rewrite_env true "$backup_directory"
  echo "Prepared production secret files without removing legacy fallback keys."
  echo "Legacy fallback keys will be removed only after deployment health verification."
  exit 0
fi

backup_directory=""
if [ -f "$state_marker" ]; then
  backup_directory="$(sed -n 's/^backup_directory=//p' "$state_marker" | tail -n 1)"
fi
if [ -z "$backup_directory" ]; then
  backup_directory="$(create_env_backup)"
fi
rewrite_env false "$backup_directory"
echo "Removed legacy inline secret keys after successful deployment verification."
