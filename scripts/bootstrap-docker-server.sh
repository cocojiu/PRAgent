#!/usr/bin/env sh
set -eu

APP_DIR="${APP_DIR:-/opt/repoguard}"
APP_USER="${APP_USER:-repoguard}"
APP_GROUP="${APP_GROUP:-repoguard}"
HTTP_PORT_BIND="${HTTP_PORT_BIND:-80}"
HTTPS_PORT_BIND="${HTTPS_PORT_BIND:-443}"
PRODUCTION_ORIGIN="${PRODUCTION_ORIGIN:-http://CHANGE_ME_SERVER_IP}"
PRODUCTION_SERVER_NAME="${PRODUCTION_SERVER_NAME:-CHANGE_ME_SERVER_IP}"
SECRETS_DIR="$APP_DIR/secrets"

need_root() {
  if [ "$(id -u)" -ne 0 ]; then
    echo "Please run as root, or use sudo." >&2
    exit 1
  fi
}

detect_os() {
  if [ ! -f /etc/os-release ]; then
    echo "Cannot detect OS: /etc/os-release not found." >&2
    exit 1
  fi
  . /etc/os-release
  OS_ID="${ID:-}"
  OS_LIKE="${ID_LIKE:-}"
}

install_docker_debian() {
  apt-get update
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
    curl -fsSL "https://download.docker.com/linux/${OS_ID}/gpg" | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/${OS_ID} ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

install_docker_rhel() {
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y dnf-plugins-core
    dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  elif command -v yum >/dev/null 2>&1; then
    yum install -y yum-utils
    yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    echo "Neither dnf nor yum found; install Docker manually." >&2
    exit 1
  fi
}

install_docker_if_needed() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    echo "Docker and Compose plugin already installed."
    return
  fi

  case "$OS_ID" in
    ubuntu|debian)
      install_docker_debian
      ;;
    centos|rhel|rocky|almalinux)
      install_docker_rhel
      ;;
    *)
      case "$OS_LIKE" in
        *debian*)
          install_docker_debian
          ;;
        *rhel*|*fedora*)
          install_docker_rhel
          ;;
        *)
          echo "Unsupported OS for automatic Docker install: ${OS_ID}" >&2
          echo "Install Docker Engine and Docker Compose plugin manually, then rerun this script." >&2
          exit 1
          ;;
      esac
      ;;
  esac
}

ensure_user() {
  if ! getent group "$APP_GROUP" >/dev/null 2>&1; then
    groupadd --system "$APP_GROUP"
  fi
  if ! id "$APP_USER" >/dev/null 2>&1; then
    useradd --system --gid "$APP_GROUP" --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
  fi
  usermod -aG docker "$APP_USER" || true
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 48 | tr -d '\n'
  elif [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
    od -An -N48 -tx1 /dev/urandom | tr -d ' \n'
  else
    echo "No cryptographically secure random source is available." >&2
    return 1
  fi
}

write_secret_if_missing() {
  target="$1"
  value="$2"
  if [ -e "$target" ]; then
    if [ ! -f "$target" ] || [ ! -s "$target" ]; then
      echo "Existing secret path must be a non-empty regular file: $target" >&2
      exit 1
    fi
    echo "Keep existing $target"
  else
    printf '%s' "$value" > "$target"
  fi
  # Compose bind-mounts local secrets without remapping ownership. Keep the
  # parent directory private and make the file readable by the image's
  # non-root runtime user.
  chmod 444 "$target"
}

write_initial_secret_files() {
  install -d -m 700 "$SECRETS_DIR"
  write_secret_if_missing "$SECRETS_DIR/mysql.root-password" "$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/spring.datasource.password" "$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/repoguard.security.encryption-key" "RG!1-$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/repoguard.security.encryption-salt" "$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/repoguard.auth.token-secret" "$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/app.security.admin-api-key.key" "$(random_secret)"
  write_secret_if_missing "$SECRETS_DIR/app.github.webhook.secret" "$(random_secret)"
}

write_env_if_missing() {
  if [ -f "$APP_DIR/.env" ]; then
    echo "Keep existing $APP_DIR/.env"
    if grep -Eq '^(MYSQL_ROOT_PASSWORD|MYSQL_PASSWORD|REPOGUARD_SECURITY_ENCRYPTION_KEY|REPOGUARD_SECURITY_ENCRYPTION_SALT|REPOGUARD_AUTH_TOKEN_SECRET|REPOGUARD_ADMIN_API_KEY|REPOGUARD_GITHUB_WEBHOOK_SECRET)=' "$APP_DIR/.env"; then
      echo "Existing inline secrets require the README production migration procedure before the next deployment." >&2
    fi
    return
  fi

  write_initial_secret_files

  cat > "$APP_DIR/.env" <<EOF
BACKEND_IMAGE=ghcr.io/your-org/pragent-backend:latest
FRONTEND_IMAGE=ghcr.io/your-org/pragent-frontend:latest

MYSQL_ROOT_PASSWORD_FILE=./secrets/mysql.root-password
MYSQL_DATABASE=repoguard
MYSQL_USER=repoguard
MYSQL_PASSWORD_FILE=./secrets/spring.datasource.password

RABBITMQ_DEFAULT_USER=repoguard
RABBITMQ_DEFAULT_PASS=$(random_secret)
RABBITMQ_DEFAULT_VHOST=/repoguard

REPOGUARD_FRONTEND_SERVER_NAME=${PRODUCTION_SERVER_NAME}
APP_CORS_ALLOWED_ORIGINS=${PRODUCTION_ORIGIN}
REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE=./secrets/repoguard.security.encryption-key
REPOGUARD_SECURITY_ENCRYPTION_KEY_ID=prod-001
REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE=./secrets/repoguard.security.encryption-salt
REPOGUARD_AUTH_TOKEN_SECRET_FILE=./secrets/repoguard.auth.token-secret
REPOGUARD_ADMIN_API_KEY_FILE=./secrets/app.security.admin-api-key.key
REPOGUARD_ADMIN_API_KEY_ENABLED=true
REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE=./secrets/app.github.webhook.secret
REPOGUARD_GITHUB_WEBHOOK_ALLOWED_REPOSITORIES=
REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES=PRAgent-test
REPOGUARD_GITHUB_WEBHOOK_REQUIRE_SIGNATURE=true
REPOGUARD_GITHUB_WEBHOOK_IGNORE_DRAFT=true

HTTP_PORT_BIND=${HTTP_PORT_BIND}
HTTPS_PORT_BIND=${HTTPS_PORT_BIND}
HEALTH_URL=http://127.0.0.1/healthz
EOF
  chmod 600 "$APP_DIR/.env"
}

check_port() {
  for port in "$HTTP_PORT_BIND" "$HTTPS_PORT_BIND"; do
    if command -v ss >/dev/null 2>&1 && ss -ltn | awk '{print $4}' | grep -Eq "(:|\\])${port}$"; then
      echo "Warning: TCP port ${port} appears to be in use." >&2
      echo "If nginx/apache is running, stop it or change port binds in $APP_DIR/.env." >&2
    fi
  done
}

main() {
  need_root
  detect_os
  install_docker_if_needed
  systemctl enable --now docker
  ensure_user

  mkdir -p "$APP_DIR/scripts"
  chown -R "$APP_USER:$APP_GROUP" "$APP_DIR"
  chmod 750 "$APP_DIR"

  write_env_if_missing
  chown "$APP_USER:$APP_GROUP" "$APP_DIR/.env"
  if [ -d "$SECRETS_DIR" ]; then
    chown -R "$APP_USER:$APP_GROUP" "$SECRETS_DIR"
    chmod 700 "$SECRETS_DIR"
  fi
  check_port

  docker --version
  docker compose version

  cat <<EOF

RepoGuard Docker server bootstrap completed.

Next steps:
1. Upload docker-compose.prod.yml to:
   $APP_DIR/docker-compose.prod.yml
2. Upload scripts/deploy-prod.sh to:
   $APP_DIR/scripts/deploy-prod.sh
3. Edit production values:
   sudoedit $APP_DIR/.env
4. In GitHub repository secrets, set:
   DEPLOY_HOST=<server-ip>
   DEPLOY_USER=<ssh-user>
   DEPLOY_PORT=22
   DEPLOY_SSH_KEY=<private-key-for-deploy-user>
   DEPLOY_PATH=$APP_DIR
5. Run GitHub Actions workflow "Release Images" with deploy=true.

If GHCR packages are private, also set:
   REMOTE_REGISTRY_USERNAME=<github-username>
   REMOTE_REGISTRY_PASSWORD=<github-token-with-read:packages>
EOF
}

main "$@"
