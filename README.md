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

- JDK：25
- Maven：3.9.9+（低于 4.0.0）
- Node.js：建议使用 Node 22，最低要求 `>=20.19.0`
- Docker / Docker Compose：用于启动 MySQL、RabbitMQ 和本地观测组件

仓库根目录提供 `.nvmrc`，前端开发建议使用该版本。

## 快速启动

启动后端依赖：

```bash
cd repoguard-backend
docker compose up -d
```

启动后端：

```bash
cd repoguard-backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev,local
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
mvn test
```

后端打包：

```bash
cd repoguard-backend
mvn -DskipTests package
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
- `REPOGUARD_SECURITY_ENCRYPTION_KEY`
- `REPOGUARD_SECURITY_ENCRYPTION_KEY_ID`
- `REPOGUARD_AUTH_TOKEN_SECRET`
- `REPOGUARD_ADMIN_API_KEY`
- `REPOGUARD_GITHUB_WEBHOOK_SECRET`
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
- 当前认证/Webhook 限流和 Dashboard 快照仍是进程本地状态，因此 API/combined 角色要求 `REPOGUARD_API_INSTANCE_COUNT=1`。迁移到共享限流与跨节点缓存失效前，不允许横向扩展 API。
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

本地可以使用 Loki + Promtail + Grafana 查看 RepoGuard 日志：

```bash
docker compose -f docker-compose.observability.yml up -d
```

Grafana 地址：

```text
http://localhost:3000
```

默认账号：`admin`

默认密码见本地 `docker-compose.observability.yml` 配置；首次使用后建议立即修改。

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
- 准出门禁分层、失败处理和执行矩阵详见 [生产准出检查自动化说明](./docs/release/16-生产准出检查自动化说明.md)。

## 优化进度

- 2026-07-21 23:21:20（Asia/Shanghai）：启动 Overview CDN/LCP 与 CLS 第六轮闭环优化。阿里云 ESA 免费版已为 `pragent.top` 的 `/` 和 `/repoguard/*` 启用 120 秒边缘 HTML 缓存，浏览器继续遵循源站 `no-cache`；重复请求已验证 `MISS → HIT`，`/api/v1/auth/me` 保持 `401 DYNAMIC`，`/actuator/health` 保持 `200 DYNAMIC`，现有登录态正常。切换后的受控桌面热样本在链路瞬时抖动（TTFB 2617 ms）下出现 LCP 8668 ms、CLS 0.2001，代码侧进一步统一 154px 指标骨架/实卡最小高度、390px 图表卡和 300px 空状态/图表内容高度，并预留稳定滚动条槽，避免摘要与图表异步数据到达时改变首屏几何；性能诊断新增不采集业务文本的最大单次布局偏移元素与前后矩形归因，便于精确确认残余 CLS 来源。已通过前端类型检查、Lint、31 个测试文件共 110 项测试、生产构建和包体预算；首屏 JavaScript/CSS 为 90.3/150.0 KiB 与 11.8/24.0 KiB gzip，待 JDK 25 CI、精确镜像部署及生产 LCP/CLS 重复复测。
- 2026-07-21 15:56:43（Asia/Shanghai）：完成 Overview 首次启动关键链路第五轮优化实现。精确镜像 `main-2232ab4445b8` 的部署后可见桌面样本为首次路由 LCP 3124 ms、热缓存 LCP 296 ms、CLS 0.0019；新增导航阶段、入口资源缓存/传输时序和不采集文本内容的 LCP 元素归因后，可直接区分 DNS/TLS/TTFB、入口下载、应用启动与路由渲染耗时。生产核查确认 HTML 与哈希 JS/CSS 原先均未返回 `Cache-Control`，现由 Nginx 对 `/assets/` 返回一年期 `public, immutable` 缓存，对 SPA HTML 保持 `no-cache`，且不覆盖 API、Grafana 与 Actuator 的上游缓存语义。默认 Overview 从异步路由前置为静态入口，将该路由增量 JavaScript/CSS/请求从 10.6 KiB、2.2 KiB、12 个收敛为 0，同时首包 JavaScript/CSS 由 82.2/10.1 KiB 增至 90.3/11.7 KiB gzip，仍分别低于 150/24 KiB 预算；以小幅首包增长换取消除约 614 ms 的默认路由瀑布。已通过前端类型检查、Lint、31 个测试文件共 110 项测试、生产构建和包体预算；本机 JDK 26 按 release 25 编译并仅跳过本地 JDK 精确版本门禁后，后端全量 1710 项测试通过（0 失败、3 跳过），待 JDK 25 CI、精确镜像部署及生产缓存头与 LCP/CLS 复测。
- 2026-07-20 23:48:52（Asia/Shanghai）：完成 Overview 冷启动关键链第四轮分包收敛。精确镜像 `main-98765d67ec1f` 的生产受控 3G 桌面复测为冷加载 LCP 4820 ms、热加载 LCP 2300 ms、CLS 0.0019，菜单、4 张图表及控制台均正常；继续定位发现通用 `vendor` 分组虽然已排除 Element Plus 本体，却仍把其 Popper、async-validator 和 lodash 传递依赖与首屏 Lucide 图标打入同一同步包。Vite 分包边界现同时排除这些仅由懒加载业务组件使用的传递依赖，避免首屏为未访问的表单和定位能力付费；8 个业务页的独立 CSS 也从 `main.ts` 全局入口归回各自懒加载路由。相对当前生产构建，首屏 JavaScript 从 97.8 KiB 降至 82.2 KiB gzip（下降约 16.0%），CSS 从 14.4 KiB 降至 10.1 KiB gzip（下降约 29.9%）；相对本次 LCP 优化前的 116.8/16.4 KiB，累计下降约 29.6%/38.4%。Overview 关键 JavaScript/CSS 保持 10.6/60.0 KiB 与 2.2/12.0 KiB，请求数保持 12/16，ECharts 最大异步包保持 131.3/140.0 KiB，未以增加首屏路由请求换取包体数字。已通过完整生产准出：后端 JDK 25 切片 78 项、前端 31 个测试文件共 110 项测试、类型检查、Lint、生产构建及包体预算均通过，待精确镜像部署和最终生产冷/热复测。
- 2026-07-20 22:40:51（Asia/Shanghai）：启动 Overview 冷启动关键链第三轮优化。上一精确镜像 `main-b8866340875d` 已将布局稳定到 CLS 0.0017，受控桌面热加载 LCP 为 448 ms，但两个首次加载样本仍为 4896 ms 和 6820 ms，瓶颈集中在初始应用壳资源与业务摘要开始之间。顶栏通知和用户菜单由首屏 Element Plus Popover/Dropdown 改为 Vue 原生、可通过 ARIA 识别且支持点击外部与 Escape 关闭的轻量弹层，保留通知按需加载、修改密码、设置和退出等既有行为；通知后台预热由 1.2 秒延后至 12 秒，避免与摘要及首批图表争用冷网络。生产构建的首屏 JavaScript 从 116.8 KiB 降至 97.8 KiB gzip（下降约 16.3%），CSS 从 16.4 KiB 降至 14.4 KiB gzip（下降约 12.2%）；Overview 关键 JavaScript/CSS 为 10.6/60.0 KiB 与 2.2/12.0 KiB，请求数 12/16，ECharts 最大异步包保持 131.3/140.0 KiB。已通过完整生产准出：后端 JDK 25 切片 78 项、前端 31 个测试文件共 110 项测试、类型检查、Lint、生产构建及包体预算均通过，待精确镜像部署和生产冷/热样本复测。
- 2026-07-20 22:01:43（Asia/Shanghai）：完成 Overview LCP 优化后的生产 CLS 回归诊断与等高骨架修复。精确镜像 `main-dd4dcbdb84c2` 首轮受控 3G 桌面样本将 LCP 从优化前 3804 ms 降至 736 ms（重复热样本 428 ms），路由关键 JavaScript 保持 10.2 KiB gzip，图表资源收敛为 EChartPanel、ECharts、zrender 3 个缓存请求；同时诊断发现 CLS 0.173，定位为摘要响应前指标网格高度为 0，4 张指标卡在 1280px 两列布局出现后把下方内容整体下推约 331px。`MetricGrid` 新增可选的 4 卡等高骨架与 `aria-busy` 状态，Overview 在首次摘要就绪前从首帧开始预留网格空间；骨架 CSS 合并进首屏样式，未增加 Overview 关键请求，首屏 JavaScript/CSS 为 116.8/150.0 KiB 与 16.4/24.0 KiB，Overview 关键 JavaScript/CSS 为 10.2/60.0 KiB 与 2.2/12.0 KiB、请求数 11/16。已通过前端 31 个测试文件共 110 项测试、类型检查、Lint、生产构建和包体预算，待精确修复镜像部署后复测 CLS。
- 2026-07-20 20:56:42（Asia/Shanghai）：完成单用户生产环境的自助修改密码入口与 Overview LCP 加载瀑布优化。用户菜单新增按需加载的修改密码对话框，前端校验与后端 8–128 位、字母和数字、确认一致及不可复用当前密码的约束对齐；修改成功后清除本地凭据、重置当前用户并跳转登录页，服务端继续负责轮换会话版本、撤销刷新令牌和清除认证 Cookie。Overview 改为摘要指标优先，在两个动画帧后加载趋势、风险和规则主模块，再于浏览器空闲期异步挂载 LLM 质量、风险列表和健康状态；`DeferredEChartPanel` 在图表依赖下载期间保持稳定占位高度，避免容器折叠引发 CLS，并补充加载失败重试及计时取消边界。ECharts 由多个细碎异步块合并为单一 131.3/140.0 KiB gzip 块以缩短串行资源瀑布；Overview 关键 JavaScript 由约 37.7 KiB 降至 10.0 KiB（下降约 73.5%），关键 CSS 由 7.4 KiB 降至 2.2 KiB，请求数由 14 降至 11，首屏 JavaScript/CSS 分别为 116.7/150.0 KiB 与 16.3/24.0 KiB。已通过 JDK 25 下后端生产准出切片 78 项、前端 31 个测试文件共 110 项测试、类型检查、Lint、生产构建和完整生产准出检查；优化前受控 3G 桌面基线为 LCP 3804 ms、CLS 0.0227，待精确镜像部署后复测生产 Overview。
- 2026-07-20 15:22:48（Asia/Shanghai）：完成 P3 第三阶段单用户受控性能诊断闭环。仅在显式传入有效 `performanceProfile=desktop|mobile|weak-network|custom` 时异步加载 2.21 KiB gzip 的诊断模块，常规模式的轻量桥接直接返回，不注册 Observer、动画帧或额外计时；动态模块加载前发生的路由与图表事件通过有界队列回放，避免丢失早期时序。诊断采用 buffered LCP、交互延迟近似 INP、CLS 会话窗口算法，统一记录路由首帧、Overview 数据就绪、ECharts 激活/首次 `finished` 渲染，以及图表资源的网络/缓存来源和传输字节，并同时提供页面内全局快照与隐藏 JSON 输出，便于受限自动化稳定采集。已通过前端 31 个测试文件共 110 项测试、类型检查、Lint、生产构建和完整生产就绪检查（后端准出切片 78 项通过）；首屏 JavaScript 为 115.8/150.0 KiB gzip、CSS 为 16.3/24.0 KiB gzip，Overview 增量 JavaScript 为 37.5/60.0 KiB gzip、CSS 为 7.4/12.0 KiB gzip、请求数为 13/16，最大异步包为 56.2/140.0 KiB gzip。本地生产包登录页烟测得到 LCP 176 ms、CLS 0 且无控制台错误；因本地缺少生产认证与业务数据，未将该结果作为 Overview 结论，待本次精确镜像部署后采集生产 Overview 冷/热加载重复样本，再以数据决定是否进一步拆分 ECharts。
- 2026-07-19 20:18（Asia/Shanghai）：完成 P3 第二阶段首个业务路由加载瀑布优化。新增可取消的 `DeferredEChartPanel`，将 ECharts/zrender 从 Overview 静态依赖闭包迁移到图表接近视口 240px 且浏览器空闲后加载，并覆盖无 IntersectionObserver/idle API 的降级路径、卸载取消和 reduced-motion 占位状态；Dashboard 与 LLM 质量图表均切换到该边界。相邻路由预取由进入页面 1.2 秒后调整为页面完整加载后的 3 秒空闲窗口，切换路由时取消并重新校验当前路由、页面可见性与网络条件。Vite 构建门禁新增 Overview 增量关键闭包预算（JavaScript 60 KiB gzip、CSS 12 KiB gzip、13/16 请求实测/上限），该路由增量 JavaScript 从 232.0 降至 37.4 KiB gzip（下降 83.9%），关键请求从 22 降至 13（下降 40.9%），CSS 为 7.4 KiB gzip；约 195.7 KiB gzip 图表运行时已移出路由关键链。首屏 JavaScript 为 115.3/150.0 KiB gzip、CSS 为 16.3/24.0 KiB gzip，最大异步包为 zrender 56.2/140.0 KiB gzip。已通过新增延迟挂载/卸载取消回归、前端 30 个测试文件共 108 项测试、类型检查、Lint、生产构建及完整生产就绪检查（后端准出切片 78 项通过）。下一阶段结合真实用户 LCP/INP 与资源时序数据评估图表运行时的进一步细分，避免以请求碎片化换取无效的包体数字。
- 2026-07-19 17:29（Asia/Shanghai）：完成 P3 第一阶段前端首屏包体安全余量优化。引入 `unplugin-vue-components` 与 `ElementPlusResolver`，将 25 个 Element Plus 全局组件和 `v-loading` 指令改为按 Vue 模板及懒加载路由编译期注入，`main.ts` 仅保留消息类服务的公共样式；移除会把所有页面组件重新拉回首屏的 Element Plus 强制分组，并在通用 vendor 规则中排除 `element-plus`/`@element-plus`，继续保留 ECharts/zrender 异步分包。首屏 JavaScript 从 176.3 降至 114.5 KiB gzip（下降约 35%），CSS 从 27.0 降至 16.3 KiB gzip（下降约 40%），最大异步 JavaScript 仍为 zrender 56.2 KiB gzip；同步将首屏 JS/CSS 硬预算由 190/32 KiB 收紧至 150/24 KiB，并把低余量预警线从 10% 提高至 15%。已通过干净 `npm ci`、前端 29 个测试文件共 106 项测试、类型检查、Lint、生产构建、登录/注册浏览器烟测（无未解析 `el-*` 标签及控制台错误）和完整生产就绪检查（后端准出切片 78 项通过）。下一阶段评估首个业务路由的异步依赖总量与空闲预取策略，避免首屏入口变轻后由路由级 ECharts/Element Plus 聚合形成新的加载瀑布。
- 2026-07-19 15:58（Asia/Shanghai）：完成 P2-3 第二阶段用户管理 HTTP 与认证主体边界。`UserManagementController` 直接依赖 `UserManagementLifecycle` 并在 Web 适配层完成命令、分页和响应 DTO 映射，移除过渡 `UserManagementService`、`UserManagementServiceImpl`、`UserOperationAuditContext` 及对应服务适配测试，外部 JSON 契约保持不变。新增零项目实现依赖的 `AuthenticatedPrincipal` 与 `RequestAuthenticationAttributes` 中立认证契约，由 Bearer/API Key 安全过滤器生产、`RequestAuthentication` 统一供 Web 消费；认证、评审、用户管理 Controller、授权拦截器和管理审计不再识别 `AuthTokenFilter`/`AuthTokenService` 内部类型。架构门禁由 9 项扩展至 12 项，新增认证契约纯度、Web 与令牌实现隔离、用户管理 Controller 直连应用端口约束。已在 JDK 25 下通过定向回归 147 项、后端全量 `mvn verify`（1710 项测试、0 失败、3 跳过，706 个类覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 29 个测试文件共 106 项测试、类型检查、Lint 与生产构建通过）；首屏 JavaScript 为 176.3/190.0 KiB gzip，余量低于 10%。下一阶段优先按需加载 Element Plus 并继续拆分 ECharts/zrender，恢复首屏包体安全余量。
- 2026-07-19 15:07（Asia/Shanghai）：完成 P2-3 第一阶段 user 用户管理应用边界。新增纯 Java `UserManagementLifecycle` 公共端口及 `user.internal.DefaultUserManagementLifecycle` 私有实现，将用户分页、创建、角色/状态变更、会话失效协作、操作审计与写事务从技术 `service.impl` 收归 user 边界；`UserManagementServiceImpl` 仅保留 DTO/端口映射。新增中立 `PasswordHasher` 凭据端口，由 security 适配实现，user 与 identity 不再依赖具体密码服务；通用 HTTP 管理审计归位 web，认证限流器改为接收已解析客户端 IP，清除 `security -> web`、`user -> security`、`user -> web`、`web -> user` 四条循环依赖基线。原公开用户管理辅助类全部迁入 `user.internal`，架构门禁扩展到 9 项并约束内部实现可见性、公共 API 类型纯度及 user 对 security/web 的隔离；CI 发现并修复事务实现类 `final` 导致生产 CGLIB 代理无法创建的问题，新增类代理回归保护。已在 JDK 25 下通过定向回归 94 项、后端全量 `mvn verify`（1710 项测试、0 失败、3 跳过，覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 29 个测试文件共 106 项测试、类型检查、Lint 与生产构建通过）；首屏 JavaScript 为 176.3/190.0 KiB gzip，仍在预算内但余量低于 10%。下一阶段让用户管理 Controller 直接面向应用端口并移除过渡 `service` DTO 门面，同时继续收敛 Web 身份上下文与 security 实现之间的依赖。
- 2026-07-19 14:14（Asia/Shanghai）：完成 P2-2 第四阶段 identity 会话失效命令边界。新增 `IdentitySessionInvalidator` 公共窄端口并由 `IdentitySessionLifecycle` 继承，以 `REFRESH_TOKENS_ONLY`、`SESSION_VERSION_ONLY`、`ALL_SESSIONS` 三种显式策略统一账户会话失效语义；用户管理仅注入窄端口，角色变更和禁用账户同时轮换会话版本并撤销活动刷新令牌，重新启用账户只轮换版本，密码变更因原子 SQL 已同步轮换版本而只请求撤销刷新令牌。会话版本轮换改为数据库 `coalesce(session_version, 0) + 1` 原子更新并校验影响行数，所有动作继续以 REQUIRED 传播加入用户管理/账户事务；`UserManagementServiceImpl` 不再接触刷新令牌 Mapper 或会话实体，最后一个共享 `UserAccountSessionInvalidator` 及旧测试已移除。已在 JDK 25 下通过定向回归 60 项、后端全量 `mvn verify`（1697 项测试、0 失败、3 跳过，覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 106 项测试及生产包体预算通过）。下一阶段建立 user 自有的用户管理应用边界，把账户创建、角色/状态写入和管理审计从技术 `service.impl` 收拢到领域端口，并以端口替换对 security/web 实现的直接依赖，继续消减 `user` 包循环。
- 2026-07-19 13:24（Asia/Shanghai）：完成 P2-2 第三阶段 identity 账户生命周期边界。新增 `IdentityAccountLifecycle` 应用端口与私有实现，将公开注册、当前身份查询、密码变更、密码哈希、账户审计及账户写事务从 `AuthServiceImpl` 迁入 identity；`AuthServiceImpl` 现仅依赖账户、凭据和会话三个 identity 端口并负责 DTO 映射。身份审计组件同步归属 `identity.internal`，会话实现不再依赖 user 包的审计/会话辅助类，新增架构门禁禁止 identity 反向依赖 user 实现。注册和密码变更继续使用独立新事务，令牌签发与会话撤销以 REQUIRED 加入账户事务，失败审计提交语义保持不变。已在 JDK 25 下通过后端全量 `mvn verify`（1695 项测试、0 失败、3 跳过，覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 106 项测试及生产包体预算通过）。下一阶段让用户管理边界通过 identity 应用端口请求角色/状态变更后的会话失效，并移除共享会话辅助类。
- 2026-07-19 12:44（Asia/Shanghai）：完成 P2-2 第二阶段 identity 会话边界。新增 identity 自有的不可变账户与令牌值对象、`IdentitySessionLifecycle` 应用端口及私有实现，将访问/刷新令牌签发、刷新轮换、并发重放宽限、令牌复用失效、刷新令牌重置、注销和密码变更后的会话撤销从 `AuthServiceImpl` 迁入 identity 边界；凭据端口不再暴露持久化 `UserAccount`，架构门禁同步禁止 identity 公开 API 依赖 Entity/Mapper。事务传播保持注册发令牌加入账户事务，登录/刷新/重置使用独立新事务，确保刷新失败先提交复用失效动作再返回 401。已在 JDK 25 下通过后端全量 `mvn verify`（1685 项测试、0 失败、3 跳过，覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 106 项测试及生产包体预算通过）。下一阶段继续迁移注册、密码变更和当前身份查询，并收敛 user/identity 之间的账户会话协作端口。
- 2026-07-19 02:16（Asia/Shanghai）：完成 P2-2 第一阶段 identity 可执行边界。新增 `IdentityCredentialAuthenticator` 应用端口及仅允许 identity 领域访问的私有实现，将账号/邮箱凭据校验、真实/哑元密码统一校验、登录失败计数、账户锁定和 LOGIN/TOKEN_RESET 成功失败审计从 `AuthServiceImpl` 迁出，并保持失败审计独立事务与成功发令牌事务语义不变；源码级架构测试同步禁止 Controller 依赖 Mapper/Entity，并阻止其他领域导入 `identity.internal`。已在 JDK 25 下通过后端全量 `mvn verify`（1684 项测试、0 失败、3 跳过，覆盖率门禁通过）和完整生产就绪检查（后端准出切片 78 项、前端 106 项测试及生产包体预算通过）。下一阶段继续迁移会话令牌生命周期，并以 identity 自有值对象替换端口中的过渡 `UserAccount` 类型。
- 2026-07-19 00:56（Asia/Shanghai）：完成 P2-1 第一阶段横向扩展契约。运行配置由两个独立布尔开关收敛为互斥 `api|worker|combined` 角色，并增加 `monolith|split` 部署模式、单 API 实例硬约束、Worker/Scheduler 装配边界和旧开关迁移校验；生产 Compose 与部署脚本同步更新，隔离烟测保留旧开关兼容路径且未修改测试脚本。仓库治理同时收紧为仅允许跟踪 `README.md`，详细优化报告保留在本地忽略文件和 Git 历史中。已在 JDK 25 下通过后端全量 `mvn verify`（1675 项测试、覆盖率门禁通过）以及完整生产就绪检查（前端 106 项测试和生产包体预算通过）。
- 2026-07-10：完成 P3 前端性能上报输入约束优化，在 API 边界限制观测批次数量、文本长度、HTTP 状态码、耗时、字节数和计数范围；已通过 `mvn "-Dtest=FrontendPerformanceControllerTest,FrontendPerformanceObservationServiceImplTest,ApiContractTest,ControllerAuthorizationContractTest" test`。

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.
