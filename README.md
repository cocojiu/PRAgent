# PRAgent

RepoGuard Agent 是面向 GitHub Pull Request 的代码审查辅助系统，包含 Spring Boot 后端与 Vue 3 管理台。系统通过 RabbitMQ 异步执行审查任务，结合规则和 LLM 生成审查发现，并支持结果展示、人工确认、评论预览、GitHub 回写和本地观测。

## 核心功能

- PR 审查任务创建、重试、状态追踪和详情展示。
- GitHub open PR 选择、diff 拉取、行评论和 PR 总评回写。
- 规则审查与 LLM 审查结合，支持 fallback、结果解析、Finding 去重和风险画像。
- RabbitMQ 异步执行、发布确认、失败补偿和异常任务运维。
- 用户认证、管理员 API Key、RBAC、审计日志和敏感配置加密。
- Dashboard 指标、质量趋势、通知运维、消息队列健康和日志观测。

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

镜像构建和质量检查通过 GitHub Actions 的 `Release Images` workflow 执行。生产部署默认关闭，不会因构建或测试自动连接服务器；需要时由维护者在 GitHub Actions 中手动启用，并在受保护环境配置凭据。

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
- `/api/v1/github/webhooks`：GitHub `pull_request` webhook 自动触发审查任务。
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
