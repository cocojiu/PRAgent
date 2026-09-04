# PRAgent

RepoGuard Agent 是面向 GitHub Pull Request 的代码审查辅助系统，包含 Spring Boot 后端与 Vue 3 管理台。系统通过 RabbitMQ 异步执行审查任务，结合规则和 LLM 生成审查发现，并支持结果展示、人工确认、评论预览、GitHub 回写和本地观测。

## 核心功能

- PR 审查任务创建、重试、状态追踪和详情展示。
- GitHub open PR 选择、diff 拉取、行评论和 PR 总评回写。
- 规则审查与 LLM 审查结合，支持 fallback、结果解析、Finding 去重和风险画像。
- RabbitMQ 异步执行、发布确认、失败补偿和异常任务运维。
- 用户认证、管理员 API Key、RBAC、审计日志和敏感配置加密。
- Dashboard 指标、质量趋势、通知运维、消息队列健康和日志观测。
- 企业版租户与仓库管理台：租户切换、GitHub App installation 绑定、成员/OIDC 绑定和配额管理。

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
- `.github/workflows/`：质量检查、镜像构建和仓库治理工作流。
- `.github/workflow-catalog.txt`：CI、发布和维护入口的引用清单与时长基线。

## 环境要求

- JDK：25（Maven Enforcer 会拒绝其他主版本）
- Maven：3.9.9+（低于 4.0.0）；无需全局安装，仓库内 Wrapper 固定使用 3.9.16
- Node.js：建议使用 Node 22，最低要求 `>=20.19.0`
- Docker / Docker Compose：用于启动 MySQL、RabbitMQ 和本地观测组件

仓库根目录提供 `.nvmrc`，前端开发建议使用该版本。后端构建统一通过 Maven Wrapper 进入：

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

前端质量检查和构建：

```bash
cd repoguard-frontend
npm run quality
npm run build
```

后端 Controller 或 DTO 契约变更后，重新生成前端 OpenAPI 客户端元数据：

```bash
cd repoguard-frontend
npm run generate:api
```

仓库级代码质量检查：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/production-readiness-check.ps1 -Mode quick -SkipBackendTests
```

该检查用于代码、迁移和仓库治理门禁，不代表生产环境验收。

## 配置说明

常用的非敏感运行参数包括：

- `SPRING_PROFILES_ACTIVE`
- `REPOGUARD_RUNTIME_ROLE`：`combined`、`api` 或 `worker`
- `REPOGUARD_DEPLOYMENT_MODE`：`monolith` 或 `split`
- `REPOGUARD_API_INSTANCE_COUNT`
- `REPOGUARD_RATE_LIMIT_STORE`：单实例使用 `local`，多实例使用 `database`
- `REPOGUARD_REVIEW_WORKER_CONCURRENCY`
- `WORKER_CPU_LIMIT`、`BACKEND_MEM_LIMIT`、`WORKER_MEM_LIMIT`
- `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_REPOSITORIES`
- `REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES`
- `REPOGUARD_GITHUB_CHECK_RUN_ENABLED`：启用 GitHub Checks 合并门禁（需要 GitHub App）
- `REPOGUARD_GITHUB_CHECK_RUN_NAME`：分支保护中配置的必需状态检查名称，默认 `RepoGuard PR Review`

敏感配置必须通过本地未跟踪的环境文件、文件化 Secret 或受保护的 CI Secret 注入。README 不保存真实密码、令牌、API Key、私钥、主机地址、主机指纹、备份位置或镜像仓库凭据；示例只允许使用占位符。缺少必要凭据时，应用和工作流应保持 fail-closed。

需要使用文件化 Secret 时，应用支持以 `*_FILE` 形式传入路径，例如：

- `MYSQL_ROOT_PASSWORD_FILE`
- `MYSQL_PASSWORD_FILE`
- `REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE`
- `REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE`
- `REPOGUARD_AUTH_TOKEN_SECRET_FILE`
- `REPOGUARD_ADMIN_API_KEY_FILE`
- `REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE`

不要把这些变量的实际值写入 Git、命令参数、Shell 历史、容器镜像或日志。

GitHub Checks 合并门禁：

1. GitHub App 需要 `Checks: Read and write`、`Pull requests: Read and write`、`Contents: Read` 和 `Metadata: Read` 权限，并订阅 `pull_request`、`check_run` webhook。
2. 设置 `REPOGUARD_GITHUB_CHECK_RUN_ENABLED=true`，并确保租户仓库绑定到该 App installation；系统会按 `queued → in_progress → completed` 顺序写入 Check Run。
3. 在 GitHub 分支保护规则中，将 `REPOGUARD_GITHUB_CHECK_RUN_NAME`（默认 `RepoGuard PR Review`）设为必需状态检查。BLOCK Finding 会产生 failure annotation，人工复核期间为 `action_required`；Check Run 页面自带的 Re-run 会触发新一轮审查。

企业版租户与 RBAC：

1. 将前端 edition 设置为 `enterprise-experimental`，登录后平台管理员从“租户与仓库”进入控制台；所有写操作继续受服务端角色和乐观版本校验保护。
2. `PLATFORM_ADMIN` 管理租户控制面，`TENANT_ADMIN` 管理租户成员，`RULE_ADMIN` 管理规则，`REVIEWER` 执行审查，`READ_ONLY` 只读；`ADMIN`/`VIEWER` 作为兼容角色保留。
3. 顶部租户切换器把选择写入 `X-RepoGuard-Tenant` 请求头，不把租户标识放入 URL；启用 `REPOGUARD_TENANCY_ENABLED=true` 后，服务端只接受当前用户拥有的租户成员关系。

仓库级语义上下文：

1. LLM 审查会从变更文件提取类型/文件名符号，在 GitHub 默认分支树中确定性检索同目录调用方、实现/接口、测试和运行配置，并将命中内容作为关联上下文。
2. 检索使用受限的 Caffeine 缓存和内容/文件/时间预算，不执行仓库代码；GitHub 未配置、默认分支不可用或请求失败时自动降级为仅使用 PR 变更上下文。
3. 可通过 `REPOGUARD_LLM_SEMANTIC_INDEX_ENABLED`、`REPOGUARD_LLM_SEMANTIC_INDEX_MAX_FILES`、`REPOGUARD_LLM_SEMANTIC_INDEX_MAX_FILE_BYTES`、`REPOGUARD_LLM_SEMANTIC_INDEX_MAX_TOTAL_BYTES`、`REPOGUARD_LLM_SEMANTIC_INDEX_TIMEOUT_MS` 和缓存大小/TTL 变量调整预算。当前实现只做符号和路径检索，向量检索留待后续评估。

GitHub suggestion 一键修复：

1. 只有定位到变更行的 finding 才允许生成建议；`fixExample` 必须是完整替换代码块（例如 ```java ... ```）或 `suggestion:` 前缀，最多 5 行、4,000 字符，普通自然语言会被拒绝。
2. 评论中会显示原生 `suggestion` 代码块和“请先确认”提示，作者可在 GitHub 页面确认后应用；系统不会自动提交作者分支，也不会为不完整证据创建修复 PR。
3. 删除文件、PR 总评、已发布/非 actionable finding 和包含嵌套代码围栏或控制字符的内容不会生成可应用建议。

LLM 评测与模型发布中心：

1. 企业管理员可在 `/api/v1/config/review-calibration/release-center` 查看按租户隔离的影子版本、灰度版本、当前版本、历史质量对比和当月 token/费用预算；版本只接收数据集 ID、版本和 SHA-256 指纹等聚合证据，不保存原始 prompt 或模型响应。
2. 通过 `/shadow` 登记候选版本，只有 precision ≥ 90%、recall ≥ 80%、锚定率 ≥ 95%、重复率/解析失败率 ≤ 5%、p95 ≤ 15 秒且数据集质量门禁通过后，才能按 1–99% 灰度或 100% 全量发布；同一租户同一 `releaseKey` 会幂等更新。
3. 灰度路由使用审查任务 ID 的确定性桶分配，重试不会改变模型；运行时发现质量指标不安全或月度预算耗尽会自动回滚/停用 LLM 并回退规则审查。租户配额中的 `monthlyLlmTokenBudget` 与 `monthlyLlmCostBudget` 为 0 表示不设上限。
4. 受控数据集清单可使用 `PROVISIONAL_REAL_PR` 运行单仓库、20～49 个真实 PR 的小样本验收；仍须完成授权、脱敏、人工复核、固定/滚动分片和 SHA-256 指纹。报告会标记为 `PROVISIONAL`，可查看、比较和导出，但不能注册 Shadow 或晋级 Canary/Active。正式 `REAL_PR` 门禁仍要求 2～3 个仓库和 50～100 个样本。

运行角色边界：

- `combined` 同时提供 HTTP API、RabbitMQ 消费者和受数据库保护的定时任务。
- `api` 只提供 HTTP API；`worker` 负责消息消费和后台任务。
- `monolith` 必须搭配 `combined`；`split` 必须使用 `api` + `worker`。
- 横向扩展 API 前必须确认共享限流、数据库连接和缓存失效策略已经启用。

## 本地日志观测

本地可以使用 Loki、Alloy 和 Grafana 查看 RepoGuard 日志。管理员密码只在当前 Shell 中临时设置，不要写入 README 或仓库文件：

```bash
export DOCKER_SOCKET_GID="$(stat -c '%g' /var/run/docker.sock)"
read -r -s GRAFANA_ADMIN_PASSWORD
export GRAFANA_ADMIN_PASSWORD
docker compose -f docker-compose.observability.yml up -d
```

Grafana 地址：

```text
http://localhost:3000
```

默认账号：`admin`

如果后端通过 Maven 或 IDE 本地启动，可将日志写入仓库根目录的 `logs/backend`：

```powershell
cd repoguard-backend
$env:REPOGUARD_LOG_PATH = "../logs/backend"
.\mvnw.cmd spring-boot:run
```

## 镜像发布与回滚

普通 PR 只进入 `Pull Request Quality` CI 主链路；镜像发布仅由 `main`/`master`、`v*` 标签或手动 `Release Images` 触发，`PRAgent-test` 推送不会发布镜像。生产部署默认关闭，不会因构建或测试自动连接服务器；需要时由维护者在 GitHub Actions 中手动启用，并在受保护环境配置凭据。各维护工作流的手动/定时入口和调用关系见 `.github/workflow-catalog.txt`。

手动启用部署时应遵循以下边界：

1. 只使用已经通过质量门禁的镜像标签或 digest。
2. 服务器、镜像仓库、SSH 和备份凭据只从受保护的 Secret/Environment 读取。
3. 主机密钥必须离线核验并启用严格校验，禁止运行时自动信任未知主机。
4. 回滚只选择已验证的旧镜像，不删除数据库或消息队列数据卷。
5. 任一凭据、镜像或健康检查缺失时立即停止，保持 fail-closed。

本项目按个人项目范围维护，不做生产环境验收；生产工作流保持默认关闭，代码和 CI 门禁不构成线上部署证明。

## API 入口

主要后端接口前缀为 `/api/v1`：

- `/api/v1/auth/**`：注册、登录、刷新、当前用户、登出。
- `/api/v1/dashboard/**`：总览统计、趋势、风险分布、通知摘要。
- `/api/v1/reviews/**`：审查任务列表、详情、手动触发、重试、评论预览与回写。
- `/api/v1/github/webhooks`：GitHub `pull_request` 自动触发审查，`check_run.rerequested` 支持页面重新运行。
- `/api/v1/config/**`：系统设置、集成配置、连接测试和密钥重加密。
- `/api/v1/message-queue/**`：RabbitMQ 健康、异常任务和重新入队。
- `/api/v1/users/**`：用户管理。
- `/actuator/health`、`/actuator/info`、`/actuator/metrics`：健康检查和指标。

API 响应通常使用统一 `ApiResponse` 包装。业务错误优先使用稳定 `ErrorCode`。

## 开发规范

- 代码和配置中的凭据必须使用占位符；真实密钥、令牌、连接信息和本地日志不得提交。
- 只提交根目录 `README.md` 作为项目说明；其他 Markdown 和测试辅助脚本保持本地忽略。
- 后端代码使用 Maven Wrapper，前端代码使用仓库锁定的 Node.js 版本。
- OpenAPI 契约变更必须同步生成元数据和对应的后端/前端测试。
- 生产部署默认不执行；需要时必须由维护者手动启用并使用受保护凭据。

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.
