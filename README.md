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

后端 Controller 或 DTO 契约变更后，重新生成前端 OpenAPI 客户端元数据：

```bash
cd repoguard-frontend
npm run generate:api
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
- `REPOGUARD_SECRET_RE_ENCRYPTION_BATCH_SIZE`
- `REPOGUARD_SECRET_RE_ENCRYPTION_LEASE_SECONDS`
- `REPOGUARD_SECRET_RE_ENCRYPTION_RETRY_DELAY_SECONDS`
- `REPOGUARD_SECRET_RE_ENCRYPTION_MAX_ATTEMPTS`
- `REPOGUARD_SECRET_RE_ENCRYPTION_POLL_INTERVAL_MS`
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
- `REPOGUARD_RATE_LIMIT_STORE`
- `REPOGUARD_REVIEW_WORKER_CONCURRENCY`
- `WORKER_CPU_LIMIT`
- `BACKEND_MEM_LIMIT`
- `WORKER_MEM_LIMIT`

敏感配置要求：

- GitHub Token、LLM API Key、数据库密码、RabbitMQ 密码等不得提交到仓库。
- 本地 `application-local.yml`、真实 `.env`、真实密钥文件不得提交。
- 生产环境必须使用独立加密密钥、认证 Token 密钥和 GitHub Webhook Secret。

运行角色与横向扩展边界：

- `REPOGUARD_RUNTIME_ROLE` 只接受 `combined`、`api`、`worker`。`combined` 同时提供 HTTP API、RabbitMQ 消费者和受数据库栅栏保护的定时任务；`worker` 同时承载消费者与这些定时任务。
- `REPOGUARD_DEPLOYMENT_MODE` 只接受 `monolith`、`split`。`monolith` 必须搭配 `combined`；`split` 的 API 容器必须使用 `api`，Worker 容器固定使用 `worker`。配置冲突会在 Spring 启动或生产部署拉取镜像前失败。
- `REPOGUARD_RATE_LIMIT_STORE` 默认为 `local`，此时 API/combined 角色仍要求 `REPOGUARD_API_INSTANCE_COUNT=1`。横向扩展 API 前必须改为 `database`：认证、管理 API Key 失败和 Webhook 的固定窗口会由 MySQL 原子计数，并以认证密钥 HMAC 后的桶键存储；限流阈值是跨实例总限额，不需要按实例数调低。Dashboard 聚合使用数据库日快照和持久化脏版本，手工评审最终幂等由数据库唯一键保障。
- 当 `REPOGUARD_API_INSTANCE_COUNT>1` 时，认证账户状态不使用进程内 `AuthAccountCache`，每次认证从 MySQL 读取账号状态和 `session_version`；密码修改、账号禁用或注销会话后，其他 API 实例会立即拒绝旧 access token。单实例仍使用 5 秒进程内缓存，并依靠事务提交后的失效回调缩短状态生效延迟。
- 单机生产模板默认启用 `COMPOSE_PROFILES=worker-split`：API 容器使用 `api` 角色，Worker 容器使用 `worker` 角色，两者运行在同一服务器并共享 MySQL/RabbitMQ。这样不能消除主机单点，但能避免大 PR 的 CPU/内存压力直接拖垮 HTTP API。
- 单机生产模板将 `REPOGUARD_REVIEW_WORKER_CONCURRENCY` 设为 1，容器默认限制为 `WORKER_CPU_LIMIT=1.0`；单任务 LLM 分块、线程池和 bulkhead 并发默认均为 2。不要在 2C4G 主机上提高这些值，调整前必须同时验证 API P99、Worker RSS/GC、RabbitMQ oldest age 和 MySQL 延迟。
- Worker 执行链路具备 RabbitMQ、数据库 CAS、领取标识和租约保护；所有 `@Scheduled` 入口由 Scheduler 能力契约保护，避免与普通消息消费者的装配边界混淆。当前固定容器名和单机资源预算不支持直接使用 `--scale`。
- Webhook 任务以 `organization/repository/PR` 维护最新 head generation，并以 GitHub `pull_request.updated_at`（统一转 UTC）拒绝乱序旧事件；新 commit 到达后，同一 PR 的旧 `QUEUED/PUBLISH_FAILED/REQUEUE_PENDING` 任务会被合并为 `SUPERSEDED`，Worker 领取时还会用 generation + commit 做数据库栅栏。Worker 获取 diff 前后都会读取 GitHub 权威 head；发现不一致时会终止旧 Attempt、校正本地 head 并确保当前 commit 进入补偿发布链路，因此旧消息不会继续调用 LLM，漏序 Webhook 也不会永久漏审。手工触发任务不参与该合并。
- 每次实际执行都会创建不可变的 `review_execution_attempt`。文件和 Finding 按 attempt 追加写入，仅通过 `current_attempt` 切换当前结果，不再删除重试历史；`GET /api/v1/reviews/{taskId}/attempts` 列出执行，`GET /api/v1/reviews/{taskId}/attempts/{attemptId}` 查询该次文件、Finding、版本、token、成本和分阶段耗时。
- 完整执行硬预算默认 600 秒，LLM 子流水线预算默认 480 秒，并预留 30 秒数据库持久化窗口；每个事务按单调剩余预算动态设置 Spring/MyBatis 查询超时，MySQL 另有 10 秒锁等待、30 秒查询/Socket 上限。超时会保存已取得的文件/Finding并标记 `PARTIAL`，不会把不完整结果伪装成成功或无限重试。租约回收会把旧 attempt 标记为 `ABANDONED`，下一次执行创建新 attempt。
- RabbitMQ review v3 队列启用 0–10 优先级：手工触发/人工重试为 8，Webhook 为 4，恢复补偿为 3；prefetch 和 Worker 并发仍为 1。超过 300 个文件或 15000 行变更的 PR 自动降级为确定性规则评审并标记 `PARTIAL`，防止单个超大 PR 长时间占用单机 LLM 容量。
- 非当前 Attempt 的文件/Finding 默认保留 90 天，之后批量清理并记录 `payload_purged_at`；Attempt 版本、阶段耗时、失败分类等元数据默认保留 180 天。当前 Attempt、运行中 Attempt，以及已被 GitHub 评论发布记录引用的 Attempt 不参与提前清理，完整任务仍服从原任务保留策略。
- 单机 API/Worker 的 Hikari 连接池分别默认最多 8/4 个连接、最少空闲 2/1 个；相对 MySQL `max_connections=60` 保留了迁移、备份和人工运维余量。不要把两边的最大值独立拉满。
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
- 主加密密钥轮换：先完成数据库备份和恢复验证，在维护窗口调用 `/api/v1/config/secrets/re-encryption` 创建 `execute=false` 预演任务，通过 `/api/v1/config/secrets/re-encryption/jobs/{jobId}` 轮询状态并分页检查 `/items` 明细；预演以 `COMPLETED` 完成且失败数为 0 后，使用 `confirmText=RE-ENCRYPT` 创建执行任务。任务按主键分页、短事务和数据库 lease 运行，可通过 `/pause`、`/resume` 暂停或继续；只有执行任务以 `COMPLETED` 完成且失败数为 0 后，才可原子替换密钥文件、更新 key ID并重建后端。新实例验证所有集成配置可解密前保留旧密钥的离线副本。
- 历史明文业务密钥迁移：仅在维护窗口临时设置 `REPOGUARD_SECURITY_ALLOW_PLAINTEXT_SECRETS=true`，通过同一后台任务完成预演与执行；确认任务明细无明文、无失败后立刻恢复为 `false` 并重建后端。

### MySQL 恢复

- 日常使用 `Production MySQL Backup` workflow；只有加密、SHA-256 校验、隔离恢复、逐表检查和待发布 Flyway SQL 演练全部成功的备份才可作为恢复点。
- 恢复前记录目标镜像 tag、备份文件和校验文件 SHA-256，停止业务写入；先在 `--network none` 的临时 MySQL 容器和临时卷中恢复验证，禁止直接把未验证 SQL 导入生产卷。
- 生产恢复必须在独立维护窗口执行，保留原卷只读快照或可回切副本；恢复后校验表集合、精确行数、`CHECK TABLE`、Flyway 版本和外部 `/actuator/health/readiness`，最后再恢复流量。
- `Production MySQL Binlog Archive` 每小时强制切换一个 binlog，将尚未确认离站的已关闭 ROW binlog 加密并保存为 14 天 Artifact，Artifact 上传成功后才推进服务器确认水位。点时间恢复只能对名称匹配 `repoguard-mysql-pitr-*` 的隔离容器运行 `scripts/restore-prod-mysql-pitr.sh`，先导入带 `--source-data=2` 坐标的全量备份，再回放 binlog 到指定 UTC 秒。

### RabbitMQ 堆积与出箱补偿

- 先查看管理台“消息队列”或 `GET /api/v1/message-queue/health`，区分 `publish_failed`、`requeue_pending`、执行超时和 DLQ；同时检查 RabbitMQ/Worker 健康、磁盘和消费者数。
- 先修复根因，再通过受管理端点 `POST /api/v1/message-queue/tasks/{taskId}/requeue` 逐项重入队。不得直接清空队列、批量修改任务状态或重复投递仍在领取租约内的任务。
- 评审发布由 `next_publish_retry_at` 和补偿器自动重试；通知发布查看通知事件页，修复通道后使用 `POST /api/v1/notification-events/{id}/retry`。若补偿量持续上升，保留数据库出箱行并检查 `review_publish_compensation` / `notification_publish_compensation` 日志，不要绕过状态机直发 RabbitMQ。

### Grafana 访问与 Worker 扩容边界

Grafana 只绑定服务器 `127.0.0.1:3000`，应用 Nginx/Caddy 不提供 `/grafana` 公网路径。使用 SSH 隧道访问：

```bash
ssh -N -L 3000:127.0.0.1:3000 <deploy-user>@<production-host>
```

本机打开 `http://127.0.0.1:3000`。预置的 `RepoGuard Review Observability` 仪表盘统一查询 API 与 Worker 容器日志，可通过 `taskId`、`traceId` 和 `operation` 关联完整评审链路。不得把 Grafana 端口改为 `0.0.0.0`；确需 Web 入口时必须先增加 SSO 或 IP allowlist，并单独评审拓扑。

默认部署使用一个 API 容器、一个 Worker 容器、`REPOGUARD_API_INSTANCE_COUNT=1` 和本地限流。当前 Compose 使用固定 `container_name`，不得直接使用 `--scale`。吞吐不足时先观测数据库连接、RabbitMQ 未确认消息、LLM bulkhead、API P99 和内存，再小步调整容量；只有迁移到多服务器后，才进入数据库共享限流、多实例日志采集和多 Worker 容量验证。

## 生产数据库备份

生产 MySQL 逻辑备份通过 GitHub Actions 的 `Production MySQL Backup` workflow 执行：每 6 小时运行一次，也可手动触发。工作流复用 production environment 的 SSH 部署凭据，把受限备份与轮换脚本上传到服务器后运行；数据库密码只在 MySQL 容器内部通过 `MYSQL_PWD` 使用，备份加密密码只通过 SSH 标准输入传递，两者均不会写入命令参数或日志。定时运行强制执行隔离恢复验证；手动关闭恢复验证时不会执行保留策略。

- 备份以 `--single-transaction --quick --routines --triggers --events --source-data=2` 创建带 binlog 坐标的一致性逻辑快照，经 gzip 压缩后使用 AES-256-CBC、PBKDF2-SHA-256 和 200000 次迭代加密。
- 加密文件和独立 SHA-256 校验文件先保存到服务器 `/opt/repoguard/backups/mysql/`，目录权限为 `0700`；隔离恢复通过后，工作流会精确下载本次密文及校验文件、再次校验 SHA-256，并保存为保留 30 天的 GitHub Actions Artifact。这样即使唯一服务器和本地备份同时损坏，仍有服务器外恢复点；Artifact 中只有已加密密文，没有明文 SQL。
- 默认在无网络、限制 CPU/内存的临时 MySQL 容器和专用临时卷中恢复，校验源库与恢复库的表数量及表名集合，逐表执行 `CHECK TABLE`，随后按版本顺序对恢复副本执行仓库中高于生产版本的 Flyway SQL，记录迁移起止版本、数量和耗时；生产数据库不执行演练 SQL。
- 临时容器、临时卷和中间文件在成功或失败时均按固定名称前缀清理；生产数据库只读，不创建演练 schema。
- 日常备份仅在新备份加密校验、隔离恢复和生产外部健康检查均成功后执行保留策略；轮换前会校验根目录全部日常备份与 `.sha256` 文件一一对应且内容一致，按 UTC 文件名倒序保留最近 7 份。`/opt/repoguard/backups/mysql/legacy/` 被固定排除，不参与自动删除；轮换后再次检查备份数量、当前备份 SHA-256 和生产健康。
- 历史明文备份通过 `Production MySQL Legacy Backup Migration` workflow 处理：先以 `inventory` 只读盘点顶层 `.sql` 的路径、大小、修改时间和 SHA-256，并区分空文件与可迁移备份；再以 `encrypt` 仅对非空文件在 `/opt/repoguard/backups/mysql/legacy/` 创建加密副本，并校验密文 SHA-256 与解密后源文件 SHA-256 完全一致。该流程不删除明文；删除必须在核对精确清单后单独确认。
- 不要直接轮换 `REPOGUARD_BACKUP_ENCRYPTION_PASSWORD`。轮换前必须先重新加密仍需保留的历史备份，并在密码管理器中保存恢复密钥副本。
- MySQL 已开启 `performance_schema`。维护窗口前可执行 `bash scripts/report-prod-mysql-index-usage.sh` 读取索引使用量与可见性；V66 仅把被规范化索引替代的 3 个旧 Dashboard 索引设为 `INVISIBLE`。至少连续观察 7 天且替代索引均可见、旧索引读取为 0 后，可在维护窗口设置 `CONFIRM_DROP_LEGACY_DASHBOARD_INDEXES=drop-after-observation` 并运行 `scripts/drop-retired-prod-mysql-indexes.sh`；否则脚本拒绝删除。

## API 入口

主要后端接口前缀为 `/api/v1`：

- `/api/v1/auth/**`：注册、登录、刷新、当前用户、登出。
- `/api/v1/dashboard/**`：总览统计、趋势、风险分布、通知摘要。
- `/api/v1/reviews/**`：审查任务列表、详情、手动触发、重试、执行 Attempt 历史与结果、评论预览与回写。
- `/api/v1/github/webhooks`：GitHub `pull_request` webhook 自动触发审查任务。
- `/api/v1/config/**`：系统设置、集成配置、连接测试、密钥重加密。
- `/api/v1/message-queue/**`：RabbitMQ 健康、异常任务、重新入队。
- `/api/v1/users/**`：用户管理。
- `/actuator/health/liveness`：仅判断进程是否可自行恢复，不依赖 MySQL/RabbitMQ；`/actuator/health/readiness`：同时检查应用就绪状态、MySQL 与 RabbitMQ，用于容器、发布、备份和外部流量门禁；`/actuator/health` 保留为聚合诊断端点。`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus` 提供版本与指标。

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
{service=~"backend(-worker)?"}
{service=~"backend(-worker)?"} |= "ERROR"
{container=~"repoguard-backend(-worker)?"}
{container=~"repoguard-backend(-worker)?"} |= "traceId=<X-Trace-Id>"
{container=~"repoguard-backend(-worker)?"} |= "operation=review_execute"
{container=~"repoguard-backend(-worker)?"} |= "operation=github_diff_fetch"
{container=~"repoguard-backend(-worker)?"} |= "failureCategory="
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

- 2026-07-31 02:31:59（Asia/Shanghai）：完成 Q4 OBSERVE 固定版本人工校准入口。新增仅限管理员访问的 `/api/v1/config/review-calibration/queue`，只选取当前检测器、规则配置及活动 Prompt/上下文/Schema/验证器/聚合版本下、评估状态为 `COMPLETE` 的 HIGH/CRITICAL Finding；明确标签仍严格限定为 `VALID/FIXED/FALSE_POSITIVE`，未标注和忽略样本不进入精确率分母。队列按仓库轮转、折叠同任务精确重复项，默认返回 30 条未标注样本，并携带证据、影响、前置条件、修复建议、完整版本键、标注进度和实时质量门禁；规则配置页已提供规则切换、版本/回放/处置状态、质量指标与“查看并标注”入口，任务详情可带校准上下文返回。规则 OBSERVE→COMMENT/BLOCK 提升现复用同一固定版本、完整评估窗口，合并 Finding 的复合 rule/detector 溯源也可正确参与门禁，避免旧版本或不完整任务污染晋级判断。新增 MySQL 8 真实 SQL 集成断言、Mapper/Service/Controller、授权、OpenAPI 与前端请求契约；JDK 25 后端全量 2268 项测试通过（0 失败、0 错误、7 跳过），前端 35 个测试文件共 138 项测试、类型检查、Lint、生产构建和原包体预算通过，完整 production-readiness 通过。精确提交 `ce2968bd9bed072bc4e4738ed476ebf8dbfd52bc` 的 Release Images Run `30571033581`、Pull Request Quality Run `30571038086`、两路 Repository Governance 与 Auto Create Pull Request 均成功；云端已实际执行 Spring + MySQL + RabbitMQ 集成（包含新校准 CTE/窗口查询）、后端契约、前端质量与构建、密钥扫描、生产 Compose、双镜像构建及 HIGH/CRITICAL 漏洞扫描，测试分支部署按策略跳过。本批仍不自动调级、不发布评论、不解除阻断门槛；下一步从规则配置页逐条完成人工标注。

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.

## 企业生产上线（P0）

企业部署基线位于 `deploy/kubernetes/`，使用外部托管 MySQL 8 和 RabbitMQ，API、Worker、前端分别扩容。清单默认启用租户隔离、数据库限流、关闭自助注册，并要求容器以非 root、只读根文件系统和 `restricted` Pod Security 运行。部署前必须将清单中的三个零值镜像 digest 替换为发布流水线生成的真实 digest，并将示例域名和 TLS Secret 改为企业域名。

### 上线顺序

1. 创建两份启用 Versioning 与 Object Lock 的主/异地对象存储桶，分别绑定独立 KMS Key ARN；先手工运行 `Production MySQL Backup`，确认恢复演练、V76 迁移演练和双目标 COMPLIANCE 对象校验全部成功。
2. 在预发布数据库执行 V1–V76。V68 创建租户、成员、仓库和企业身份表，将历史业务数据回填到不可变默认租户 `id=1`；V69 进一步租户化密钥重加密作业/明细和后台清理审计；V70 创建带过期接管能力的全局/逐租户定时作业租约表；V71 为租约增加单调 fencing token；V72 创建每租户单行、单调递增的缓存版本表；V73 为租户增加受约束的暂停/恢复状态、单调状态版本、原因和变更时间；V74 创建每租户每日审查配额配置和用量表，并为历史租户回填默认每日 1000 次额度；V75 为通知 outbox 持久化 traceId；V76 以兼容旧应用的 expand 阶段为租户父子关系补齐组合候选键和子表组合索引，暂不移除旧的单列外键。配额用量默认保留 90 天，由逐租户保留任务按批清理并写入操作审计，可通过 `REPOGUARD_TENANT_QUOTA_USAGE_RETENTION_DAYS` 调整。Worker 每 60 秒用数据库时钟续期，只有当前 owner 与 token 能续期或释放；租约上下文随有界执行器传播并引用计数，失租后中断在途线程且禁止进入后续批次。缓存变更与租户版本在同一写事务内原子提交，每个 API/Worker 副本以 1 秒间隔分页扫描版本；发现新租户或版本变化时只清理该租户的全部本地业务缓存和仪表盘快照，不依赖可能乱序的自增事件游标，也不会产生无界事件日志。迁移必须按项目真实 Flyway SQL mode 在 MySQL 8.0 从空库完整演练。
3. 创建 Kubernetes Secret 后执行 `kubectl apply -k deploy/kubernetes`。API 副本通过 Flyway schema history 锁串行迁移；Worker 固定设置 `SPRING_FLYWAY_ENABLED=false`，且 schema version guard 要求数据库达到 V76。生产默认租约 900 秒、心跳 60 秒；心跳间隔必须小于租约时长。缓存失效轮询器必须在每个副本启用，不得接入全局定时租约，否则其他副本会保留陈旧的本地缓存。清单同时启用 API/Worker/前端 startupProbe，避免 Flyway、JVM 或静态资源冷启动被 liveness 误重启；Worker 滚动更新保持 `maxUnavailable=0`，命名空间通过 ResourceQuota/LimitRange 限制总资源，并默认拒绝出站流量，仅放行 DNS、HTTPS、MySQL 和 RabbitMQ 端口。
4. 等待 `repoguard-api`、`repoguard-worker` 和 `repoguard-frontend` 全部 Ready，再验证登录、OIDC、Webhook、人工审查、消息消费和 GitHub 回写。前端镜像固定访问集群内 `backend:8081` Service。
5. 最后启用 HPA 告警和定时备份。完整加密备份每 6 小时执行一次；关闭的 binlog 每小时归档一次，只有主/异地对象都通过 KMS、SHA-256、Object Lock 和回读校验后才更新远端确认点。

V68–V76 没有自动 down migration。创建第二个租户之前，可用旧应用版本配合默认租户数据回滚；V72 的版本表、V74 的配额表、V75 的通知 trace 字段和 V76 的附加索引可被旧版本安全忽略，但回滚期间只能依赖原有本地缓存 TTL，且不得继续创建需要配额校验的新审查。V73 之后的旧二进制不理解暂停语义，禁止用于回滚；只能先停止写入并从上线前已验证备份恢复。

### Kubernetes Secret 契约

`repoguard-enterprise-env` 至少提供以下环境变量：

- `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`
- `SPRING_RABBITMQ_HOST`、`SPRING_RABBITMQ_PORT`、`SPRING_RABBITMQ_USERNAME`、`SPRING_RABBITMQ_PASSWORD`
- `APP_CORS_ALLOWED_ORIGINS`
- `REPOGUARD_ENTERPRISE_OIDC_ISSUER_URI`、`REPOGUARD_ENTERPRISE_OIDC_AUDIENCE`
- `REPOGUARD_GITHUB_APP_APP_ID`、`REPOGUARD_GITHUB_APP_ALLOWED_INSTALLATION_IDS`
- `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_REPOSITORIES`、`REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES`
- `REPOGUARD_SECURITY_ENCRYPTION_KEY_ID`、`REPOGUARD_AUTH_TOKEN_SECRET_ID`

`repoguard-enterprise-files` 通过 Spring configtree 挂载到 `/run/secrets`，键名必须使用完整属性名：

- `repoguard.security.encryption-key`
- `repoguard.security.encryption-salt`
- `repoguard.auth.token-secret`
- `app.security.admin-api-key.key`
- `app.github.webhook.secret`
- `repoguard.github-app.private-key`

私钥使用 PKCS#8 或 PKCS#1 RSA PEM。OIDC issuer 必须为 HTTPS，token 必须包含配置的 audience 和默认 `amr=mfa`；身份不会按邮箱自动创建，必须由平台管理员预先绑定 issuer 与 subject。

### 平台租户控制面

平台租户 API 只接受内置管理员 API Key 形成的 `id=0 / admin-api-key` 主体；普通租户的 ADMIN 角色不能调用。入口如下：

- `POST /api/v1/enterprise/tenants`：创建租户、初始管理员和租户本地默认配置。
- `GET /api/v1/enterprise/tenants`：按页列出租户，可按 `ACTIVE/SUSPENDED` 状态筛选。
- `GET /api/v1/enterprise/tenants/{tenantKey}`：读取状态版本和最近一次状态变更原因。
- `PUT /api/v1/enterprise/tenants/{tenantKey}/status`：使用期望状态和版本做乐观并发的软暂停或恢复；默认租户 `id=1` 不可暂停。
- `PUT /api/v1/enterprise/tenants/{tenantKey}/memberships`：添加或更新成员角色和默认租户。
- `PUT /api/v1/enterprise/tenants/{tenantKey}/repositories`：唯一绑定 GitHub 仓库与 App installation。
- `PUT /api/v1/enterprise/tenants/{tenantKey}/identities`：绑定 OIDC issuer、subject 与本地用户。
- `GET /api/v1/enterprise/tenants/{tenantKey}/quota`：读取每日审查额度、当日已用量和配置版本。
- `PUT /api/v1/enterprise/tenants/{tenantKey}/quota`：使用期望配置版本更新每日审查额度；额度耗尽时新审查返回 429，已入队任务不重复计数。

业务请求的租户只能来自已验证 OIDC 身份、成员关系、签名 Webhook 的仓库映射或可信消息；`X-RepoGuard-Tenant` 仅能在当前用户已有成员关系中切换，不能直接指定任意租户。暂停会阻断新的业务请求、Webhook、租户定时批次以及审查/通知消息消费，并在同一事务推进租户缓存版本；已经开始的外部调用可能完成，暂停期间被消费者拒绝的旧消息进入既有 DLQ，恢复后必须按运维流程重放。所有控制面变更继续由企业管理审计拦截器记录，平台不提供硬删除租户。

### 不可变备份变量

GitHub `production` Environment 需要现有部署 SSH Secret、`REPOGUARD_BACKUP_ENCRYPTION_PASSWORD`，以及以下 OIDC/Object Storage 变量或 Secret：

- `REPOGUARD_BACKUP_ROLE_ARN`
- `REPOGUARD_BACKUP_BUCKET`、`REPOGUARD_BACKUP_REGION`、`REPOGUARD_BACKUP_KMS_KEY_ID`
- `REPOGUARD_BACKUP_REPLICA_BUCKET`、`REPOGUARD_BACKUP_REPLICA_REGION`、`REPOGUARD_BACKUP_REPLICA_KMS_KEY_ID`
- 可选 `REPOGUARD_BACKUP_ENDPOINT`、`REPOGUARD_BACKUP_REPLICA_ENDPOINT`
- 可选 `REPOGUARD_BACKUP_OBJECT_LOCK_DAYS`，允许 30–3650 天，默认 30 天

KMS Key 必须提供完整 ARN，主目标与异地目标必须不同。工作流使用 GitHub OIDC 获取短期凭据，不保存长期云访问密钥；脚本不会接受非 HTTPS 自定义 endpoint，也不会上传未通过本地 SHA-256 校验或文件名/对象键白名单的文件。

### 企业验收门槛

- 使用 JDK 25 执行 `cd repoguard-backend && ./mvnw verify`。
- 执行 `cd repoguard-frontend && npm run build`。
- 执行 `scripts/production-readiness-check.ps1`，确认 schema 默认版本与最高迁移一致。
- 手工触发完整备份和 binlog 工作流，确认主/异地对象 URI、COMPLIANCE 保留期与恢复演练结果。
- 在两个租户中使用同名 PR、规则配置和通知事件做隔离验收，并确认租户管理员无法访问平台控制面。
