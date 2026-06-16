# PRAgent

RepoGuard Agent 是面向 GitHub Pull Request 的代码审查辅助系统，包含 Spring Boot 后端与 Vue 3 管理台。

## 项目结构

- `repoguard-backend/`：Spring Boot 后端，提供审查任务、配置、GitHub PR 拉取、LLM/规则审查、评论回写等 API。
- `repoguard-frontend/`：Vue 3 + Vite + TypeScript 前端管理台。
- `docs/`：产品、测试和发布相关文档。

## 本地环境

- Node.js：建议使用 Node 22，最低要求 `>=20.19.0`。仓库根目录提供 `.nvmrc`。
- JDK：26。
- Maven：3.9+。

## 常用命令

后端测试：

```bash
cd repoguard-backend
mvn test
```

前端构建：

```bash
cd repoguard-frontend
npm run build
```

前端开发服务默认通过 Vite `/api` 代理访问后端 `http://localhost:8081`。

## 本地日志观测

本地可以使用 Loki + Promtail + Grafana 查看 RepoGuard 日志：

```bash
docker compose -f docker-compose.observability.yml up -d
```

Grafana 地址：`http://localhost:3000`

默认账号：`admin`

默认密码：`repoguard-admin`

如果后端通过 Maven 或 IDE 本地启动，建议让日志写入仓库根目录的 `logs/backend`：

```powershell
cd repoguard-backend
$env:REPOGUARD_LOG_PATH = "../logs/backend"
mvn spring-boot:run
```

Grafana 的 Explore 页面选择 `Loki` 数据源后，可以用这些查询：

```logql
{service="repoguard-backend"}
{service="repoguard-backend"} |= "ERROR"
{container="repoguard-backend"}
{container="repoguard-backend"} |= "taskId=9005"
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

