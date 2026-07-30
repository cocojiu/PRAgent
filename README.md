# PRAgent

RepoGuard Agent 是面向 GitHub Pull Request 的代码审查辅助系统，包含 Spring Boot 后端与 Vue 3 管理台。系统通过 RabbitMQ 异步执行审查任务，拉取 GitHub diff，结合规则和 LLM 生成审查发现，并支持结果展示、人工确认、评论预览、GitHub 回写和运维观测。

## 核心功能

- PR 审查任务创建、重试、状态追踪和详情展示。
- GitHub open PR 选择、diff 拉取、行评论和 PR 总评回写。
- 规则审查与 LLM 审查结合，支持 fallback、结果解析、finding 去重和风险画像。
- RabbitMQ 异步执行、发布确认、失败补偿、死信/异常任务运维。
- 用户认证、管理员 API Key、RBAC、审计日志和敏感配置加密。
- Dashboard 指标、LLM 质量趋势、通知运维、消息队列健康和日志观测。

## 界面预览

![总览页](assets/screenshots/overview.jpg)

![登录页](assets/screenshots/login.jpg)

![注册页](assets/screenshots/register.jpg)

## 技术栈

后端：

- Java 25
- Spring Boot 4
- MyBatis-Plus
- MySQL 8 + Flyway
- RabbitMQ
- Micrometer / Actuator
- JUnit / Mockito / Spring Test

前端：

- Node.js >= 20.19.0
- Vue 3
- Vite 7
- TypeScript
- Element Plus
- ECharts
- lucide-vue-next

## 项目结构

- `repoguard-backend/`：Spring Boot 后端，提供审查任务、配置、GitHub 集成、LLM/规则审查、评论回写和运维 API。
- `repoguard-frontend/`：Vue 3 + Vite + TypeScript 前端管理台。
- `config/`：本地和示例配置。

## 环境要求

- JDK：25（Maven Enforcer 会拒绝其他主版本）
- Maven：3.9.9+（低于 4.0.0）；无需全局安装，仓库内 Wrapper 固定使用 3.9.16
- Node.js：建议使用 Node 22，最低要求 `>=20.19.0`
- Docker / Docker Compose：用于启动 MySQL、RabbitMQ 和本地观测组件

仓库根目录提供 `.nvmrc`，前端开发建议使用该版本。

后端构建统一通过 Maven Wrapper 进入。安装并切换到 JDK 25 后，可先校验本机构建工具链：

```powershell
cd repoguard-backend
java -version
.\mvnw.cmd -version
.\mvnw.cmd validate
```

macOS / Linux 使用对应入口：

```bash
cd repoguard-backend
java -version
./mvnw -version
./mvnw validate
```

## 快速启动

启动后端依赖：

```bash
cd repoguard-backend
docker compose up -d
```

启动后端：

```bash
cd repoguard-backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local
```

默认后端地址：

```text
http://localhost:8081
```

启动前端：

```bash
cd repoguard-frontend
npm install
npm run dev
```

默认前端地址：

```text
http://localhost:5173
```

前端开发服务默认通过 Vite `/api` 代理访问后端 `http://localhost:8081`。

## 常用命令

后端全量测试：

```bash
cd repoguard-backend
./mvnw test
```

后端打包：

```bash
cd repoguard-backend
./mvnw -DskipTests package
```

前端构建：

```bash
cd repoguard-frontend
npm run build
```

前端开发：

```bash
cd repoguard-frontend
npm run dev
```

## 配置说明

常用环境变量：

- `SPRING_PROFILES_ACTIVE`
- `SPRING_RABBITMQ_HOST`
- `SPRING_RABBITMQ_PORT`
- `SPRING_RABBITMQ_USERNAME`
- `SPRING_RABBITMQ_PASSWORD`
- `MYSQL_ROOT_PASSWORD_FILE`
- `MYSQL_PASSWORD_FILE`
- `REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE`
- `REPOGUARD_SECURITY_ENCRYPTION_KEY_ID`
- `REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE`
- `REPOGUARD_SECURITY_ALLOW_PLAINTEXT_SECRETS`
- `REPOGUARD_AUTH_TOKEN_SECRET_FILE`
- `REPOGUARD_AUTH_TOKEN_SECRET_ID`
- `REPOGUARD_AUTH_TOKEN_SECRET_PREVIOUS`
- `REPOGUARD_AUTH_TOKEN_SECRET_PREVIOUS_ID`
- `REPOGUARD_ADMIN_API_KEY_FILE`
- `REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE`
- `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_REPOSITORIES`
- `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES`
- `REPOGUARD_GITHUB_WEBHOOK_REQUIRE_SIGNATURE`
- `REPOGUARD_GITHUB_WEBHOOK_IGNORE_DRAFT`
- `REPOGUARD_RUNTIME_ROLE`
- `REPOGUARD_DEPLOYMENT_MODE`
- `REPOGUARD_API_INSTANCE_COUNT`
- `REPOGUARD_REVIEW_WORKER_CONCURRENCY`

敏感配置要求：

- GitHub Token、LLM API Key、数据库密码、RabbitMQ 密码等不得提交到仓库。
- 本地 `application-local.yml`、真实 `.env`、真实密钥文件不得提交。
- 生产环境必须使用独立加密密钥、认证 Token 密钥和 GitHub Webhook Secret。

运行角色与横向扩展边界：

- `REPOGUARD_RUNTIME_ROLE` 只接受 `combined`、`api`、`worker`。`combined` 同时提供 HTTP API、RabbitMQ 消费者和受数据库栅栏保护的定时任务；`worker` 同时承载消费者与这些定时任务。
- `REPOGUARD_DEPLOYMENT_MODE` 只接受 `monolith`、`split`。`monolith` 必须搭配 `combined`；`split` 的 API 容器必须使用 `api`，Worker 容器固定使用 `worker`。配置冲突会在 Spring 启动或生产部署拉取镜像前失败。
- 当前认证/Webhook 限流和 Dashboard 快照仍是进程本地状态，因此 API/combined 角色要求 `REPOGUARD_API_INSTANCE_COUNT=1`。迁移到共享限流与跨节点缓存失效前，不允许横向扩展 API。认证限流阈值（`REPOGUARD_AUTH_REQUESTS_PER_MINUTE_PER_IP`、`REPOGUARD_AUTH_REQUESTS_PER_MINUTE_PER_ACCOUNT_IP`）为单实例语义，若未来放开横向扩展需按实例数调低；实际生效阈值会在 API 启动日志中打印。
- Worker 执行链路具备 RabbitMQ、数据库 CAS、领取标识和租约保护，可由编排平台扩展多个实例；当前生产 Compose 仍固定为单个 Worker 服务。所有 `@Scheduled` 入口由 Scheduler 能力契约保护，避免与普通消息消费者的装配边界混淆。
- 旧 `REPOGUARD_API_ENABLED`、`REPOGUARD_WORKER_ENABLED` 仅保留迁移兼容；新部署应改用单一角色变量。生产部署脚本会根据 Compose 服务集合推导并验证 `monolith/split`。

## 镜像发布与回滚

生产发布通过 GitHub Actions 的 `Release Images` workflow 执行。镜像会推送到 GHCR 和阿里云 ACR，生产服务器部署时从阿里云 ACR 拉取镜像。

RepoGuard 通过 GitHub `pull_request` webhook 自动创建审查任务。当前自动建 PR workflow 只监听 `PRAgent-test` 分支 push，并自动创建或复用指向 `main` 的 PR。生产环境建议将 `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_REPOSITORIES` 配置为当前 GitHub 仓库全名，并保持 `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES=PRAgent-test`。

需要在 GitHub Actions Repository secrets 或 variables 中配置：

- `ALIYUN_REGISTRY`
- `ALIYUN_DEPLOY_REGISTRY`
- `ALIYUN_NAMESPACE`
- `ALIYUN_REPOSITORY`
- `ALIYUN_REGISTRY_USERNAME`
- `ALIYUN_REGISTRY_PASSWORD`
- `DEPLOY_HOST`
- `DEPLOY_USER`
- `DEPLOY_PORT`
- `DEPLOY_PATH`
- `DEPLOY_SSH_KEY`
- `REPOGUARD_BACKUP_ENCRYPTION_PASSWORD`

正常发布：

1. 打开 GitHub Actions。
2. 选择 `Release Images`。
3. 点击 `Run workflow`。
4. 分支选择 `main`。
5. 勾选 `Deploy to the configured server after pushing images`。
6. 运行 workflow。

快速回滚：

1. 打开 `Release Images` 的 `Run workflow`。
2. 分支选择 `main`。
3. 在 `Existing image tag to deploy without rebuilding, for rollback` 填入已有镜像 tag，例如 `main-<commit12>`。
4. 勾选 `Deploy to the configured server after pushing images`。
5. 运行 workflow。

填写 `deploy_existing_tag` 时，workflow 会跳过镜像构建，直接部署 ACR 中已有的 `backend-<tag>` 和 `frontend-<tag>` 镜像。部署脚本会校验实际运行容器镜像与目标镜像一致，并在健康检查失败时输出后端最近日志。

## 生产运维 Runbook

### 部署前检查与密钥文件迁移

生产 Compose 使用服务器本地文件提供 MySQL 密码和 5 个应用密钥；RabbitMQ 口令暂时保留在 `.env`。密钥目录必须为 `0700`，文件必须为 `0444`、非空、非符号链接，且末尾不能带 CR/LF。这里的 `0444` 是 Docker Compose 本地 file secret 的运行约束：Compose 以 bind mount 提供文件且不会把宿主所有权映射给镜像内的 `repoguard` 非 root 用户；宿主侧仍由不可遍历的 `0700` 父目录提供保密边界。部署脚本会在拉取镜像或停止容器前检查目录/文件权限，并在拉取后用镜像默认非 root 用户实际验证全部 `/run/secrets/*` 可读，再拒绝包含 Grafana/Loki/Alloy 上游的边缘配置。

从旧版明文 `.env` 迁移时，不要生成新值；必须把当前正在使用的原值逐字节写入对应文件，否则已有数据库、Token 和加密业务配置会失效：

```bash
cd /opt/repoguard
umask 077
install -d -m 700 secrets
printf '%s' '<原 MYSQL_ROOT_PASSWORD>' > secrets/mysql.root-password
printf '%s' '<原 MYSQL_PASSWORD>' > secrets/spring.datasource.password
printf '%s' '<原 REPOGUARD_SECURITY_ENCRYPTION_KEY>' > secrets/repoguard.security.encryption-key
printf '%s' '<原 REPOGUARD_SECURITY_ENCRYPTION_SALT>' > secrets/repoguard.security.encryption-salt
printf '%s' '<原 REPOGUARD_AUTH_TOKEN_SECRET>' > secrets/repoguard.auth.token-secret
printf '%s' '<原 REPOGUARD_ADMIN_API_KEY>' > secrets/app.security.admin-api-key.key
printf '%s' '<原 REPOGUARD_GITHUB_WEBHOOK_SECRET>' > secrets/app.github.webhook.secret
chmod 444 secrets/*
```

唯一例外是从 2026-07-25 及更早、尚未引入 `REPOGUARD_SECURITY_ENCRYPTION_SALT` 的生产版本首次升级：此时没有可保留的旧 salt。必须在 `Release Images` 同时显式启用 `migrate_legacy_secret_files` 与 `initialize_missing_encryption_salt`，迁移脚本才会只为该缺失项生成 32 字节随机 salt；任一 salt 键或默认目标文件已经存在时都会复用并校验原值，不会轮换。该开关默认关闭，不能脱离明文密钥迁移单独使用；旧版 `enc:v1`/`enc:v2` 数据继续使用原加密密钥兼容解密，新写入才使用 salt 派生的 `enc:v3` 密钥。

随后把 `.env` 中上述明文键替换为以下文件路径键；确认新容器健康、登录/Webhook/集成配置解密和一次备份恢复均正常后，再删除服务器上的受限迁移副本：

```dotenv
MYSQL_ROOT_PASSWORD_FILE=./secrets/mysql.root-password
MYSQL_PASSWORD_FILE=./secrets/spring.datasource.password
REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE=./secrets/repoguard.security.encryption-key
REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE=./secrets/repoguard.security.encryption-salt
REPOGUARD_AUTH_TOKEN_SECRET_FILE=./secrets/repoguard.auth.token-secret
REPOGUARD_ADMIN_API_KEY_FILE=./secrets/app.security.admin-api-key.key
REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE=./secrets/app.github.webhook.secret
```

每次发布前执行：

```bash
cd /opt/repoguard
sh -n scripts/deploy-prod.sh
docker compose --env-file .env -f docker-compose.prod.yml config --quiet
BACKEND_IMAGE='<目标后端镜像>' FRONTEND_IMAGE='<目标前端镜像>' \
  ENV_FILE=.env PREFLIGHT_ONLY=true sh scripts/deploy-prod.sh
```

再通过 `Release Images` 执行发布。不要使用 `source .env`，不要把密钥放入命令参数、shell 历史、容器环境或 Git；部署工作流不会上传、备份或覆盖服务器的 `secrets/`。

### 发布失败与回滚

- 部署预检失败时尚未修改运行服务，先按首条错误修复文件、权限、角色或 Compose 配置后重试。
- 预检后的发布失败会触发 `scripts/deploy-prod.sh` 自动回滚：先恢复上一版 Compose/Caddy/RabbitMQ 配置，再恢复上一版后端、Worker 和前端镜像，并重新校验健康与发布标识。
- 自动回滚仍不健康时，保留现场，执行 `docker compose --env-file .env -f docker-compose.prod.yml ps` 和 `logs --tail=200 backend backend-worker`；不要执行 `down -v` 或清理 MySQL/RabbitMQ 卷。
- 需要人工回滚时，在 `Release Images` workflow 选择 `main`，填写已验证的 `deploy_existing_tag` 并启用部署。回滚不恢复或轮换密钥文件；密钥变更必须作为独立变更处理。

### 密钥轮换与明文配置迁移

- Token 密钥轮换：把当前文件值和当前 ID 临时配置为 `REPOGUARD_AUTH_TOKEN_SECRET_PREVIOUS` / `REPOGUARD_AUTH_TOKEN_SECRET_PREVIOUS_ID`，用临时文件加 `mv` 原子替换 `repoguard.auth.token-secret`，更新活动 ID并强制重建 API/Worker；至少等待一个 access-token TTL 后清空 previous 对。previous 值在轮换窗口内仍属于 `.env` 残余风险，窗口结束必须删除。
- 主加密密钥轮换：先完成数据库备份和恢复验证，在维护窗口调用 `/api/v1/config/secrets/re-encryption` 做 `execute=false` 预演；失败数为 0 后使用 `confirmText=RE-ENCRYPT` 执行，立即原子替换密钥文件、更新 key ID并重建后端。新实例验证所有集成配置可解密前保留旧密钥的离线副本。
- 历史明文业务密钥迁移：仅在维护窗口临时设置 `REPOGUARD_SECURITY_ALLOW_PLAINTEXT_SECRETS=true`，通过同一重加密接口完成预演与执行；确认扫描结果无明文、无失败后立刻恢复为 `false` 并重建后端。

### MySQL 恢复

- 日常使用 `Production MySQL Backup` workflow；只有加密、SHA-256 校验、隔离恢复和逐表检查全部成功的备份才可作为恢复点。
- 恢复前记录目标镜像 tag、备份文件和校验文件 SHA-256，停止业务写入；先在 `--network none` 的临时 MySQL 容器和临时卷中恢复验证，禁止直接把未验证 SQL 导入生产卷。
- 生产恢复必须在独立维护窗口执行，保留原卷只读快照或可回切副本；恢复后校验表集合、精确行数、`CHECK TABLE`、Flyway 版本和外部 `/actuator/health`，最后再恢复流量。

### RabbitMQ 堆积与出箱补偿

- 先查看管理台“消息队列”或 `GET /api/v1/message-queue/health`，区分 `publish_failed`、`requeue_pending`、执行超时和 DLQ；同时检查 RabbitMQ/Worker 健康、磁盘和消费者数。
- 先修复根因，再通过受管理端点 `POST /api/v1/message-queue/tasks/{taskId}/requeue` 逐项重入队。不得直接清空队列、批量修改任务状态或重复投递仍在领取租约内的任务。
- 评审发布由 `next_publish_retry_at` 和补偿器自动重试；通知发布查看通知事件页，修复通道后使用 `POST /api/v1/notification-events/{id}/retry`。若补偿量持续上升，保留数据库出箱行并检查 `review_publish_compensation` / `notification_publish_compensation` 日志，不要绕过状态机直发 RabbitMQ。

### Grafana 访问与 Worker 扩容边界

Grafana 只绑定服务器 `127.0.0.1:3000`，应用 Nginx/Caddy 不提供 `/grafana` 公网路径。使用 SSH 隧道访问：

```bash
ssh -N -L 3000:127.0.0.1:3000 <deploy-user>@<production-host>
```

本机打开 `http://127.0.0.1:3000`。不得把 Grafana 端口改为 `0.0.0.0`；确需 Web 入口时必须先增加 SSO 或 IP allowlist，并单独评审拓扑。

API 仍固定 `REPOGUARD_API_INSTANCE_COUNT=1`。吞吐不足时先观测数据库连接、RabbitMQ 未确认消息、LLM bulkhead 和内存，再小步调整 `REPOGUARD_REVIEW_WORKER_CONCURRENCY`；需要进程隔离时启用 `worker-split`。当前 Compose 的固定 `container_name` 只支持一个 Worker 服务，不得直接使用 `--scale`；多 Worker 实例要先移除固定名称、验证日志采集规则和容量预算，且不能横向扩展 API。

## 生产数据库备份

生产 MySQL 逻辑备份通过 GitHub Actions 的 `Production MySQL Backup` workflow 执行：每天北京时间 03:30（UTC 19:30）自动运行，也可手动触发。工作流复用 production environment 的 SSH 部署凭据，把受限备份与轮换脚本上传到服务器后运行；数据库密码只在 MySQL 容器内部通过 `MYSQL_PWD` 使用，备份加密密码只通过 SSH 标准输入传递，两者均不会写入命令参数或日志。定时运行强制执行隔离恢复验证；手动关闭恢复验证时不会执行保留策略。

- 备份以 `--single-transaction --quick --routines --triggers --events` 创建一致性逻辑快照，经 gzip 压缩后使用 AES-256-CBC、PBKDF2-SHA-256 和 200000 次迭代加密。
- 加密文件和独立 SHA-256 校验文件保存到服务器 `/opt/repoguard/backups/mysql/`，目录权限为 `0700`；工作流不上传业务数据到 GitHub Artifact。
- 默认在无网络、限制 CPU/内存的临时 MySQL 容器和专用临时卷中恢复，校验源库与恢复库的表数量及表名集合，逐表执行 `CHECK TABLE`，并记录加密文件与解密逻辑转储的 SHA-256 指纹和恢复库精确行数。
- 临时容器、临时卷和中间文件在成功或失败时均按固定名称前缀清理；生产数据库只读，不创建演练 schema。
- 日常备份仅在新备份加密校验、隔离恢复和生产外部健康检查均成功后执行保留策略；轮换前会校验根目录全部日常备份与 `.sha256` 文件一一对应且内容一致，按 UTC 文件名倒序保留最近 7 份。`/opt/repoguard/backups/mysql/legacy/` 被固定排除，不参与自动删除；轮换后再次检查备份数量、当前备份 SHA-256 和生产健康。
- 历史明文备份通过 `Production MySQL Legacy Backup Migration` workflow 处理：先以 `inventory` 只读盘点顶层 `.sql` 的路径、大小、修改时间和 SHA-256，并区分空文件与可迁移备份；再以 `encrypt` 仅对非空文件在 `/opt/repoguard/backups/mysql/legacy/` 创建加密副本，并校验密文 SHA-256 与解密后源文件 SHA-256 完全一致。该流程不删除明文；删除必须在核对精确清单后单独确认。
- 不要直接轮换 `REPOGUARD_BACKUP_ENCRYPTION_PASSWORD`。轮换前必须先重新加密仍需保留的历史备份，并在密码管理器中保存恢复密钥副本。

## API 入口

主要后端接口前缀为 `/api/v1`：

- `/api/v1/auth/**`：注册、登录、刷新、当前用户、登出。
- `/api/v1/dashboard/**`：总览统计、趋势、风险分布、通知摘要。
- `/api/v1/reviews/**`：审查任务列表、详情、手动触发、重试、评论预览与回写。
- `/api/v1/github/webhooks`：GitHub `pull_request` webhook 自动触发审查任务。
- `/api/v1/config/**`：系统设置、集成配置、连接测试、密钥重加密。
- `/api/v1/message-queue/**`：RabbitMQ 健康、异常任务、重新入队。
- `/api/v1/users/**`：用户管理。
- `/actuator/health`、`/actuator/info`、`/actuator/metrics`：健康检查和指标。

API 响应通常使用统一 `ApiResponse` 包装。业务错误优先使用稳定 `ErrorCode`。

## 本地日志观测

本地可以使用 Loki + Alloy + Grafana 查看 RepoGuard 日志。Linux 主机需先把 Docker Socket 的实际组 ID 和 Grafana 管理员密码放入当前 shell；Alloy 只通过内部只读 API 代理采集容器日志，不直接挂载 Docker Socket：

```bash
export DOCKER_SOCKET_GID="$(stat -c '%g' /var/run/docker.sock)"
export GRAFANA_ADMIN_PASSWORD='请设置一个独立强密码'
docker compose -f docker-compose.observability.yml up -d
```

Grafana 地址：

```text
http://localhost:3000
```

默认账号：`admin`

密码为启动前显式设置的 `GRAFANA_ADMIN_PASSWORD`；首次使用后建议立即修改。

如果后端通过 Maven 或 IDE 本地启动，建议让日志写入仓库根目录的 `logs/backend`：

```powershell
cd repoguard-backend
$env:REPOGUARD_LOG_PATH = "../logs/backend"
.\mvnw.cmd spring-boot:run
```

Grafana 的 Explore 页面选择 `Loki` 数据源后，可以用这些查询：

```logql
{service="repoguard-backend"}
{service="repoguard-backend"} |= "ERROR"
{container="repoguard-backend"}
{container="repoguard-backend"} |= "traceId=<X-Trace-Id>"
{container="repoguard-backend"} |= "operation=review_execute"
{container="repoguard-backend"} |= "operation=github_diff_fetch"
{container="repoguard-backend"} |= "failureCategory="
```

也可以打开 Grafana Dashboard：

```text
RepoGuard / RepoGuard Review Observability
```

该看板支持按 `taskId`、`traceId`、`operation` 过滤审查链路日志。

## 开发规范

- 提交信息使用 `<type>(<scope>): <中文摘要>` 格式。
- 提交前按影响范围运行后端测试、前端类型检查或前端构建。
- 不提交本地日志、临时脚本、真实密钥、真实 token、真实连接信息。
- 生产准出完整门禁可执行 `powershell -ExecutionPolicy Bypass -File scripts/production-readiness-check.ps1`，覆盖空白、Flyway migration、敏感信息扫描、后端关键测试集合、前端质量门禁和前端生产构建。
- 轻量仓库治理可执行 `powershell -ExecutionPolicy Bypass -File scripts/production-readiness-check.ps1 -Mode quick -SkipBackendTests`，用于只检查 tracked 文件治理、Flyway migration、demo data guard 和敏感信息扫描；旧参数 `-IncludeFrontendBuild` 仍兼容并等价于完整模式。
- 准出门禁、失败处理、回滚与恢复步骤统一维护在本文“生产运维 Runbook”。

## 优化进度

- 2026-07-30 16:57:06（Asia/Shanghai）：完成审查能力与风险校准专项 Q0 质量基线批次。新增按 `rule_id/source/repository/language/severity` 聚合的历史 Finding 质量模型，统一人工反馈口径为 `VALID + FIXED` 确认有效、`FALSE_POSITIVE` 确认误报、其余状态暂不裁定；高危占比、明确标注高危精确率/误报率、有效行锚定率、精确重复率、平均审查耗时与累计 LLM 成本现进入规则配置页既有指标响应，反馈更新会在事务提交后同步失效规则质量缓存。新增 7 条高危规则共 140 个离线黄金样本，每条固定 10 个正例和 10 个反例及预期 Finding、severity、confidence、blocking、PR 风险；当前 35 个已知语义差距被显式记录并由 141 项动态 replay 锁定，覆盖类级权限、脱敏日志、配置占位符、测试 fixture、批准的 MQ/GitHub 发布边界与兼容数据库迁移，后续 Q2 只能通过逐项消除差距更新基线。真实 MySQL 生产上下文集成用例同时验证反馈分母、维度聚合、重复与执行成本口径。定向 222 项测试通过；本机 JDK 26 使用 Maven 3.9.16 并仅跳过精确 JDK 25 Enforcer 后，后端干净全量 `clean verify` 共 2138 项测试通过（0 失败、0 错误、7 跳过，772 个类 JaCoCo 门禁通过），前端 35 个测试文件共 136 项测试、类型检查、Lint、生产构建、原包体预算及完整 production-readiness（后端已独立全量验证）全部通过；待推送后由 JDK 25 CI 执行真实 MySQL/RabbitMQ 生产上下文验证。

- 2026-07-30 18:27:31（Asia/Shanghai）：完成审查能力与风险校准专项 Q1 评级链路正确性收口批次。规则运行时配置现完整承载 severity、0-100 confidence、OBSERVE/COMMENT/BLOCK 处置模式、正例与误报指引，并通过不可变单次审查快照、内置检测器注册表和缺失/未知/重复配置门禁保证配置与执行器一一对应；动态规则创建入口已关闭，升级时会把无检测器的历史启用规则转为不可执行观察状态。14 个内置检测器统一只产出 `RuleMatch`，由 `FindingPolicyResolver` 校验变更行证据、应用配置并生成带处置原因的 `EffectiveFinding`，不再由检测器或兼容构造器把 HIGH 自动等同于高置信和阻断；同一输入仅修改规则等级即可同步改变 Finding、服务端 PR 风险和人工复核决策。新增语义去重与确定性 `ServerRiskAggregator`，LLM 根级 risk 不再形成风险地板，无有效变更行的高危候选降级且不能阻断，LLM 与规则同一问题只贡献一次并保留 `LLM+RULE` 来源。任务新增 `AssessmentStatus=COMPLETE/PARTIAL/FAILED/SUPERSEDED` 并持久化到活动表、归档表、API 与前端，执行失败/超时不再伪装成 HIGH 代码风险，不完整输入不再强制抬升 MEDIUM，Dashboard、列表和通知仅把完整评估计入代码风险分布。人工复核阈值已配置化，默认只接收高置信阻断级 HIGH/CRITICAL；MEDIUM 默认完成而不阻塞，`FALSE_POSITIVE` 反馈会在同一事务内重算任务风险、释放或恢复复核门禁，并从评论候选、风险画像和后续阻断判断中排除。V57 迁移、OpenAPI 契约、规则配置编辑页、处置模式展示及评估完整度展示同步完成。JDK 25 + Maven Wrapper 干净全量 `clean verify` 共 2156 项测试通过（0 失败、0 错误、7 跳过，786 个类 JaCoCo 门禁通过）；前端 35 个测试文件共 136 项测试、类型检查、Lint、生产构建和包体预算通过，完整 production-readiness 通过；待推送后由 CI 执行真实 MySQL/RabbitMQ 生产上下文与迁移验证。

- 2026-07-30 19:23:11（Asia/Shanghai）：完成审查能力与风险校准专项 Q2 高噪声规则上下文化批次。新增以仓库、精确 head SHA 和文件路径为键的 `ChangedFileContext`，通过文件数、单文件字节、总保留字节、总耗时以及按条目与字节双重约束的 TTL 缓存控制 GitHub 原始文件读取成本；删除、二进制、超限、排除路径和读取失败均形成显式状态，缺少完整上下文时仅保留证据未验证的中置信候选且不能阻断。生成物、依赖、构建目录、测试、demo、example 与 docs 路径现由中央策略统一排除，授权边界、可靠 MQ 发布器、GitHub 评论发布网关和脱敏方法也由同一配置集中维护。`RG-LOG-001`、`RG-SECRET-001`、`RG-AUTH-001`、`RG-MQ-001`、`RG-GH-001`、`RG-DB-002`、`RG-DB-003` 已改为基于结构化 Java 调用、赋值与参数解析、类/方法/继承权限边界、完整 SQL 语句和建表生命周期判断，关闭多行调用、静态日志模板、配置占位符、注释/字符串 SQL、兼容迁移及批准边界等误报缺口。7 条高危规则黄金集由 140 扩展到 168 个样本，每条固定 12 个正例和 12 个反例并保留兼容性与历史差距来源，169 项黄金目录与动态 replay 全部通过；Q2 定向 242 项测试通过。JDK 25 + Maven Wrapper 干净全量 `clean verify` 共 2213 项测试通过（0 失败、0 错误、7 跳过，799 个类 JaCoCo 门禁通过）；完整 production-readiness、前端 35 个测试文件共 136 项测试、类型检查、Lint、生产构建与包体预算全部通过；待推送后由精确提交 CI 执行真实 MySQL/RabbitMQ 生产上下文验证。

- 2026-07-30 20:33:40（Asia/Shanghai）：完成审查能力与风险校准专项 Q3 LLM 审查能力升级批次。Prompt 升级为 `review-prompt-v2`，严格要求只报告本次变更新引入且有证据的问题，固定输出 `review-schema-v2` 的 issueType、severity、confidence、主新增行锚点、关联文件、evidence、preconditions、impact、recommendation、reviewDimension 和 blockingCandidate；模型输出的 `isBlocking` 被 Schema 修复与映射链路显式忽略，缺字段、低置信、无有效新增行或缺少精确 head 上下文的 HIGH/CRITICAL 候选会在服务端预检降为 MEDIUM/OBSERVE。新增 `review-context-v2` 上下文构建器，按数据库迁移、安全、运行配置和交付链路风险优先级分配字符预算，生成带稳定文件路径及行号范围的完整方法/类切片，并在语义分块中保留完整文件上下文，关联本次变更中的接口、直接调用方、测试和关键配置；测试文件仅作为 LLM 佐证上下文读取，既有规则生产路径排除语义不变。启用规则的描述、正例与误报指引被压缩进策略上下文，过期 head、读取失败和预算截断均形成显式限制，Prompt、上下文、Schema 与验证器版本写入审查摘要。新增 `high-risk-verifier-v1` 对抗式二次验证，仅处理通过预检的 HIGH/CRITICAL 或 blockingCandidate，复查证据、前置条件、新增行和已有保护；拒绝、不确定、解析失败、调用不可用、预算耗尽和候选超限均安全降级而不丢失 Finding，验证通过仍由 `FindingPolicyResolver` 应用服务端处置策略，默认 COMMENT 且不能自行阻断，规则与 LLM 共识只提高置信度、不绕过策略。单块与并行分块流水线统一累计生成/验证 token、成本和验证计数，LLM 初始调用不可用时的规则 fallback 与分块局部降级保持有效。Q3 定向 98 项及解析/replay 修复回归 42 项测试通过；JDK 25 + Maven Wrapper 干净全量 `clean verify` 共 2237 项测试通过（0 失败、0 错误、7 跳过，816 个类 JaCoCo 门禁通过），完整 production-readiness 后端切片 80 项、前端 35 个测试文件共 136 项测试、类型检查、Lint、生产构建与原包体预算全部通过；待推送后由精确提交 CI 执行真实 MySQL/RabbitMQ 生产上下文、镜像与安全验证。

- 2026-07-30 20:46:37（Asia/Shanghai）：完成 Q3 云端生产上下文门禁修复。首次精确提交 CI 的真实 MySQL/RabbitMQ 用例暴露 `LlmHighRiskVerificationService` 同时包含生产与测试专用构造路径、但未显式声明 Spring 注入构造器，导致容器回退查找无参构造器；现为生产构造器增加唯一 `@Autowired` 标记，并把该包级服务纳入 `SpringBeanConstructorSelectionTest` 架构守卫。定向 6 项回归、JDK 25 干净全量 `clean verify` 2237 项测试（0 失败、0 错误、7 跳过，816 个类 JaCoCo 门禁）及完整 production-readiness（后端 80 项、前端 136 项、类型检查、Lint、生产构建与包体预算）均通过，待新精确提交 CI 复验生产上下文。

- 2026-07-30 22:31:11（Asia/Shanghai）：完成审查能力与风险校准专项 Q4 版本化质量反馈与灰度回滚批次。V58 为规则配置补齐 detector/config/policy 三类版本，为 Prompt、上下文、Schema、高危验证器和服务端风险聚合建立单活动策略快照，并把模型供应商与模型、原始/生效 severity 和 confidence、原始阻断候选、验证状态、降级/阻断原因及锚点类型贯穿审查执行、Finding 持久化、详情 API 和 GitHub 评论预览，保证每条结论可回溯到完整运行版本。质量基线现按 `rule_id/source/repository/language/severity` 与完整版本键分组统计明确标注数及覆盖率、有效/误报、precision/FPR、高危与阻断占比、误报撤销阻断数、锚定率和精确重复率；少于 30 个明确样本仅显示 `INSUFFICIENT_SAMPLE`，不生成阈值告警，达到样本门槛后才按 precision≥90%、FPR≤10%、锚定率≥95%、重复率≤5% 评估。规则语义变更、重新启用和新运行版本默认回到 OBSERVE；OBSERVE→COMMENT 要求人工标注，COMMENT→BLOCK 要求至少 30 个明确高危样本并通过全部质量门禁，禁止直接 OBSERVE→BLOCK，策略提升还必须通过 replay 且运行版本受支持。规则和全局策略均保存不可变历史快照，回滚会创建新版本、只影响新任务且不改写历史 Finding；规则配置页已提供当前版本、质量门禁、分组指标、升降级、历史版本和回滚操作。新增 4 组生命周期/迁移/回滚专项测试并同步 OpenAPI、前后端契约与授权矩阵；JDK 25 + Maven Wrapper 干净全量 `clean verify` 共 2255 项测试通过（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁通过），完整 production-readiness 后端切片 80 项、前端 35 个测试文件共 137 项测试、类型检查、Lint、生产构建与包体预算全部通过；待推送后由精确提交 CI 执行真实 MySQL 8.0.46、RabbitMQ 3.13.7、镜像与安全验证。

- 2026-07-30 22:39:04（Asia/Shanghai）：完成 Q4 云端真实数据库质量基线夹具校准。首次精确提交 CI 已成功执行全部 58 个 Flyway 迁移并启动 API/Worker 与 RabbitMQ，随后暴露生产上下文测试仍只写入旧 `line_number`、未同步写入 Q4 新增的 `anchor_type`，导致新锚定率口径把夹具数据正确识别为未锚定；现让真实 MySQL 质量样本按新增行是否存在显式写入 `ADDED_LINE/NONE`，与生产 Finding 持久化不变量一致。Q4 定向 22 项回归通过（其中本机无服务时 7 项真实依赖用例按条件跳过），JDK 25 干净全量 `clean verify` 再次完成 2255 项测试（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁）；待新精确提交 CI 复验 MySQL/RabbitMQ 锚定统计及全部发布门禁。

- 2026-07-30 23:23:24（Asia/Shanghai）：完成 Q4 OBSERVE 灰度第一步的精确镜像隔离验证入口。仓库当前只配置 production GitHub Environment，`Release Images` 也明确拒绝从 `PRAgent-test` 直接部署，因此不冒险把测试分支覆盖到生产服务；改为扩展既有 Real Chain Smoke，使其可按已发布 ACR tag 拉取后端镜像，并强制同时提供完整 40 位 expected revision，在启动独立 MySQL/RabbitMQ/Backend、临时端口和临时卷之前校验 OCI `org.opencontainers.image.revision`。新入口拒绝同时传入完整镜像与 tag、限制所有镜像坐标字符、显式登录 ACR，生产服务在隔离链路前后继续接受健康检查，Smoke 资源仍由原生命周期脚本自动清理。Workflow/YAML/隔离边界定向 20 项测试通过，JDK 25 干净全量 `clean verify` 共 2255 项测试通过（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁）；下一步使用 `pragent-test-6958f55efe15` 与完整提交 `6958f55efe151e009a10b85eec4828b92617d492` 运行三条真实 GitHub/LLM 样本。

- 2026-07-30 23:45:32（Asia/Shanghai）：完成首轮 OBSERVE 隔离验证故障收敛。Real Chain Smoke Run `30556752112` 已通过生产前置健康、ACR 精确镜像拉取及 OCI revision 校验，隔离 MySQL/RabbitMQ 也正常启动；随后确认远端旧隔离 Compose 仍读取直传加密环境变量，而生产凭据已经迁移为文件挂载，导致隔离后端因加密键为空而按预期拒绝启动。失败链路已完整删除临时容器、网络和卷，生产后置健康检查继续通过。工作流现兼容从生产 `.env` 的直传值或 `_FILE` 路径只在远端隔离进程内加载加密键与盐，以便解密复制的生产策略行；Webhook 改用每次运行生成的临时随机密钥，不复用生产 Webhook 凭据。另增加并行只读策略探针，要求活动快照精确为 strategy 1、`review-prompt-v2/review-context-v2/review-schema-v2/high-risk-verifier-v1/server-risk-v2`、`OBSERVE`、已 replay 且唯一激活，并显式输出镜像 revision。Workflow YAML 与完整 Bash 块语法校验通过，JDK 25 定向 20 项契约测试及干净全量 `clean verify` 2255 项测试通过（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁通过）；下一步推送修复后重跑同一精确业务镜像。

- 2026-07-30 23:56:04（Asia/Shanghai）：完成第二轮 OBSERVE 隔离验证兼容收敛。Real Chain Smoke Run `30558613271` 已直接输出并验证业务镜像 revision `6958f55efe151e009a10b85eec4828b92617d492`，成功加载文件化加密键与盐、恢复预 D1 数据、执行至 V58、启动隔离后端，并由只读探针确认唯一活动策略为 strategy 1、完整 Q3 运行版本、`OBSERVE`、replay 已通过；随后远端旧 Smoke 生命周期脚本在复制生产策略行时仍从生产 MySQL 容器内读取已迁移掉的 `MYSQL_PASSWORD`，因此被数据库拒绝。隔离资源再次完整清理且生产后置健康通过。工作流现从生产 `MYSQL_PASSWORD` 或 `MYSQL_PASSWORD_FILE` 取得兼容值，运行期在部署目录创建权限为 0700/0600 的临时 Docker 垫片与 env-file，只对精确生产 MySQL 容器的两个只读/导出 `docker exec` 注入旧变量，其他 Docker 调用原样转发；生命周期结束自动删除垫片，不修改生产容器和远端 Smoke 脚本。Workflow YAML、完整 Bash 块语法、JDK 25 定向 20 项契约测试及干净全量 `clean verify` 2255 项测试通过（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁通过）；下一步推送后第三次运行同一精确业务镜像。

- 2026-07-31 00:16:22（Asia/Shanghai）：完成 Q4 OBSERVE 隔离链路运行态验证并保持质量门禁关闭。Real Chain Smoke Run `30559410464` 与受控复跑 `30559971025` 均使用精确业务 revision `6958f55efe151e009a10b85eec4828b92617d492`，通过 ACR/OCI 校验、预 D1 恢复、V58 迁移、隔离 MySQL/RabbitMQ/Backend 启动、生产策略行兼容复制和唯一活动 `OBSERVE` 策略探针；两次运行的生产前后健康检查与隔离资源清理均通过，未部署或改写生产服务。小样本均正常完成；中样本 PR #12 两次均产生 3 条 Finding；当时只有任务级计数，暂不作为正误样本计入分母，后续脱敏元数据确认其在 `src/main` 新增真实写接口且缺少授权与测试，属于有效正例；旧“风险”样本 PR #13 实际只新增明确标注为临时 E2E 的假密钥 fixture，一次 LLM 超时降级、一次正常完成且 0 Finding，符合 Q2 测试/fixture 降噪语义，但触发旧 Smoke 的“必须有 Finding”断言。因此不降低门槛、不把 harmless 样本改标为风险，也不进入 COMMENT/BLOCK；下一批次先替换为经人工确认的真实正例，再按至少 30 个明确高危样本收集 precision、FPR、锚定率和重复率。工作流同时开始留存严格脱敏的 OBSERVE 摘要：无论门禁成功或失败，都在 Step Summary 与 90 天 artifact 中仅保存提交 revision、退出状态、策略版本、任务状态、token/耗时、变更文件数和 Finding 数，不保存镜像坐标、凭据、文件内容或 Finding 文本。Workflow YAML、完整 Bash 块语法、JDK 25 定向 20 项契约测试及干净全量 `clean verify` 2255 项测试均通过（0 失败、0 错误、7 跳过，834 个类 JaCoCo 门禁通过）。

- 2026-07-31 01:19:39（Asia/Shanghai）：完成 Q4 OBSERVE 全链路处置约束修复与复跑前收口。新建并固定关闭态正例 PR #132（head `c8bcd39760bf8ce75128873b61acf7793fe0b372`），在生产路径新增缺少授权守卫的写接口，远端分支保留且工作流会校验 PR、head SHA 与分支身份，避免样本漂移。Real Chain Smoke Run `30564024648` 使用旧业务 revision `6958f55efe151e009a10b85eec4828b92617d492` 完成三条任务、策略探针、生产前后健康检查及隔离资源清理；总 token 为 20615，因旧 18000 预算上限最终失败，但 90 天脱敏 artifact 成功确认 PR #12 与 #132 均产生 `RG-AUTH-001` 等有效 Finding。运行态同时暴露合并后的规则 Finding 仍保留 BLOCK/COMMENT，绕过全局 OBSERVE。现新增最终结果统一策略闸门，在成功、规则 fallback 与异常降级路径上将每条 Finding 限制到不强于已 replay 且版本受支持的全局策略，未知或未验证策略按 OBSERVE 失效闭合；降级后清除阻断标记、保留原 blockingCandidate 与执行溯源，并重新聚合服务端风险。Smoke 默认预算按实测调至 25000，新增 `smoke_observe_violations` 强断言，要求 OBSERVE 快照下所有 Finding 均为 OBSERVE 且 `is_blocking=0`。JDK 25 定向 35 项及干净全量 `clean verify` 共 2261 项测试通过（0 失败、0 错误、7 跳过，835 个类 JaCoCo 门禁通过）；下一步发布该精确提交镜像并以同一固定样本复跑，确认数据库实际处置字段与任务状态。

- 2026-07-31 01:37:40（Asia/Shanghai）：完成 Q4 OBSERVE 固定样本校准与全链路处置约束验收。精确提交/镜像 revision `bbd4cccf49bf4149b05b376ac4a3af4b0d809868` 的 Release Images Run `30565789590`、Pull Request Quality 与两路 Repository Governance 均成功；Real Chain Smoke Run `30566255192` 随后使用 tag `pragent-test-bbd4cccf49bf` 并通过完整 OCI revision 校验。三条隔离任务全部 `COMPLETED/INFO`：PR #11 使用 2810 token、0 Finding，PR #12 使用 8681 token、3 Finding，固定正例 PR #132 使用 7892 token、3 Finding，总计 19383/25000 token。脱敏摘要确认 6 条 Finding 全部实际持久化为 `OBSERVE` 且 `is_blocking=0`，`smoke_observe_violations=0`；其中 PR #12 的 `RG-AUTH-001` 二次验证为 REJECTED，但规则证据仍保留为非阻断观察候选，PR #132 的同类问题二次验证为 VERIFIED，原始高危/阻断候选、锚点及完整运行版本均未丢失。唯一活动策略探针、生产前后健康检查、隔离容器/网络/卷清理与 90 天白名单 artifact 上传全部通过，未部署、重启或改写生产服务。由此确认全局 OBSERVE 已同时约束 LLM、规则、合并、fallback 与落库路径；质量门禁继续保持 OBSERVE，下一步按版本键采集至少 30 个明确人工标签后再评估 OBSERVE→COMMENT，不以本轮 2 个正例替代统计门槛。

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.
