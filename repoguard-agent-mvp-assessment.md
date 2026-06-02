# RepoGuard Agent MVP拆分与落地评估

## 1. 评估结论

RepoGuard Agent 的项目方向成立，适合作为全栈 + 架构 + AI 工程化项目来做。它不是普通后台管理系统，也不是简单的大模型调用 Demo，而是围绕 GitHub PR Review 建立完整工程闭环：

GitHub PR 触发 -> 创建审查任务 -> RabbitMQ 异步执行 -> 拉取 PR diff -> 规则引擎分析 -> LLM 语义审查 -> 生成 Markdown 报告 -> 回写 GitHub PR 评论 -> Vue 页面展示结果。

如果目标是学习和简历展示，项目价值较高，推荐继续推进。

综合评分：

| 维度 | 评分 | 说明 |
| --- | --- | --- |
| 项目选题 | 8.5/10 | PR 质量守门员定位清晰，贴近真实工程场景 |
| 技术含金量 | 8/10 | 覆盖全栈、异步队列、外部 API、AI 工程化 |
| 一周落地可行性 | 6.5/10 | 当前范围偏满，需要裁剪 |
| 简历表达价值 | 8.5/10 | 能讲架构、幂等、重试、规则引擎、LLM 降级 |

最终建议：不要追求一周内完成完整平台，而是优先交付一个稳定可演示的 MVP 闭环。

## 2. 项目核心价值

### 2.1 闭环完整

项目从 GitHub PR 事件开始，到自动审查和 PR 评论回写结束，形成清晰的业务闭环。相比单纯生成分析报告，这个项目更接近真实生产系统。

### 2.2 规则引擎与 LLM 边界合理

确定性规则负责发现明确工程问题，例如硬编码密钥、Controller 无测试、Entity 无 migration、配置变更、调试残留。LLM 负责语义总结、风险解释和修改建议。

这种设计比纯 LLM Review 更稳定，也更容易在面试中解释误报、漏报和降级策略。

### 2.3 技术栈组合自然

| 层级 | 技术 | 价值 |
| --- | --- | --- |
| 后端 | Spring Boot 3 | 业务编排、接口、任务状态管理 |
| 前端 | Vue 3 + Vite | 审查任务列表和详情展示 |
| 数据库 | MySQL 8 | 持久化任务、文件、问题、评论记录 |
| 消息队列 | RabbitMQ | 解耦 Webhook 和耗时审查任务 |
| AI | Spring AI + Spring AI Alibaba | 接入 DashScope/百炼进行语义审查 |
| 外部平台 | GitHub API | 拉取 PR diff 并回写评论 |

## 3. 当前范围风险

### 3.1 一周版范围仍然偏大

当前一周计划同时包含：

- Spring Boot 后端
- Vue 前端
- MySQL 表设计和持久化
- RabbitMQ 主队列、重试队列、DLQ
- GitHub Webhook
- GitHub PR API
- 规则引擎
- LLM Review
- GitHub 评论回写
- Vue Dashboard
- README、截图或 GIF
- 测试仓库和 2-3 个测试 PR

这些都合理，但全部放进一周内风险较高。尤其是 GitHub Webhook、本地公网回调、RabbitMQ 重试/DLQ、LLM JSON 稳定解析，都会消耗额外调试时间。

### 3.2 Webhook 本地调试容易卡住

GitHub Webhook 需要公网回调，本地一般需要 ngrok、Cloudflare Tunnel 或临时部署环境。建议 MVP 初期先用手动触发接口跑通主链路，Webhook 放到后面接入。

### 3.3 LLM JSON 输出不可完全信任

即使 prompt 要求只输出 JSON，模型仍可能输出 Markdown、解释文本或不合法 JSON。必须设计降级策略：

- JSON 解析失败时保存原始响应和错误原因。
- 使用规则引擎结果继续生成审查报告。
- 任务状态标记为 `COMPLETED_WITH_WARNINGS`，而不是直接失败。

### 3.4 GitHub 评论幂等容易被低估

如果每次触发都创建新评论，PR 会被刷屏。MVP 至少需要避免同一个 `repo + pr + commitSha` 重复评论。完整版再考虑根据 `comment_id` 更新旧评论。

## 4. MVP 必交付清单

以下内容建议作为一周内必须完成的核心范围。

| 模块 | 必交付内容 | 验收标准 |
| --- | --- | --- |
| 手动触发 | `POST /api/reviews/manual?owner=&repo=&pr=` | 输入 GitHub 仓库和 PR 编号后能创建审查任务 |
| GitHub Client | 拉取 PR 标题、描述、head sha、changed files、patch | 能拿到真实 PR 文件变更 |
| MySQL | `review_task`、`changed_file`、`review_finding`、`github_comment` | 能持久化任务、文件、问题和评论记录 |
| RabbitMQ | Producer + Worker + 主队列 | 任务能异步执行 |
| 规则引擎 | 5 条 Java 规则 | 测试 PR 能稳定命中规则 |
| LLM Review | Spring AI Alibaba 接入，输出结构化结果 | 成功时生成总结，失败时降级为规则审查 |
| 评论生成 | Markdown 审查报告 | 报告包含风险等级、摘要、Findings、测试建议 |
| GitHub 回写 | 创建 PR 评论 | PR 下出现 RepoGuard Review 评论 |
| Vue 页面 | 任务列表页 + 任务详情页 | 能查看状态、风险等级、规则命中和 GitHub PR 链接 |

## 5. 一周可延期清单

以下内容可以放到 MVP 之后，不影响核心 Demo。

| 内容 | 延期原因 | 后续版本建议 |
| --- | --- | --- |
| GitHub Webhook | 本地公网调试成本较高 | MVP 跑通后接入 |
| 复杂 Dashboard 图表 | 展示价值不如主链路 | V1 增加风险分布、规则 Top N |
| 完整 retry queue / DLQ | RabbitMQ 调试成本较高 | MVP 先记录失败和 retry_count |
| GitHub App | 安装和权限流程复杂 | V2 做团队化时引入 |
| 多租户和权限 | 超出学习项目 MVP 范围 | 平台化阶段再做 |
| PMD / SpotBugs / Semgrep | 集成和结果归一化需要时间 | V1 作为增强亮点 |
| 自动修复代码 | 风险高且不是当前定位 | 暂不建议做 |
| 规则 YAML 配置化 | MVP 可先硬编码规则 | MVP Plus 再抽配置 |
| llm_review 独立表 | 初期可以简化 | 后续记录 token、成本、raw response |

## 6. 推荐开发路线

### 6.1 最小闭环路线

优先保证每天都有可运行版本，而不是到最后才集成。

1. 手动触发接口创建任务。
2. 拉取 GitHub PR changed files 和 patch。
3. 执行规则引擎并在控制台输出审查报告。
4. 将任务、文件和 findings 写入 MySQL。
5. 引入 RabbitMQ，把同步流程改为异步 Worker。
6. 接入 LLM Review，并实现 JSON 解析失败降级。
7. 生成 Markdown 并回写 GitHub PR 评论。
8. 实现 Vue 任务列表和详情页。
9. 最后接入 GitHub Webhook。

### 6.2 七天重排计划

| 天数 | 建议目标 | 必须验收 |
| --- | --- | --- |
| Day 1 | 项目骨架 + Docker Compose + 配置模板 | Spring Boot、Vue、MySQL、RabbitMQ 可启动 |
| Day 2 | 手动触发 + GitHub PR 拉取 + MySQL 任务模型 | 能创建任务并保存 PR 文件列表 |
| Day 3 | 规则引擎 + findings 入库 | 5 条规则至少 3 条有测试样例命中 |
| Day 4 | RabbitMQ Producer / Worker | 任务能异步执行，状态能流转 |
| Day 5 | LLM Review + 降级策略 | LLM 成功可解析，失败仍能生成规则报告 |
| Day 6 | GitHub 评论回写 + Vue 列表/详情 | PR 下出现评论，页面能看任务详情 |
| Day 7 | Webhook、Demo、README、截图/GIF | 完整 Demo 可演示 |

## 7. 建议的后端结构

```text
com.example.repoguard
├── controller
│   ├── GithubWebhookController.java
│   └── ReviewController.java
├── mq
│   ├── ReviewTaskMessage.java
│   ├── ReviewMessageProducer.java
│   └── ReviewWorker.java
├── service
│   ├── ReviewTaskService.java
│   ├── ReviewOrchestrator.java
│   ├── GithubPullRequestService.java
│   ├── RuleEngineService.java
│   ├── LlmReviewService.java
│   ├── ReviewCommentRenderer.java
│   └── GithubCommentService.java
├── rule
│   ├── ReviewRule.java
│   ├── HardcodedSecretRule.java
│   ├── ControllerWithoutTestRule.java
│   ├── EntityWithoutMigrationRule.java
│   ├── ConfigChangeRiskRule.java
│   └── DebugCodeRule.java
├── model
│   ├── PullRequestContext.java
│   ├── ChangedFile.java
│   ├── ReviewFinding.java
│   └── ReviewResult.java
└── repository
    ├── ReviewTaskRepository.java
    ├── ChangedFileRepository.java
    ├── ReviewFindingRepository.java
    └── GithubCommentRepository.java
```

## 8. 建议状态机

MVP 不需要过细状态，建议先使用以下状态：

| 状态 | 说明 |
| --- | --- |
| PENDING | 任务已创建 |
| QUEUED | 已投递 RabbitMQ |
| RUNNING | Worker 正在执行 |
| COMPLETED | 审查完成且评论成功 |
| COMPLETED_WITH_WARNINGS | 审查完成，但 LLM 或评论存在降级 |
| FAILED | 超过重试次数或关键步骤失败 |

完整版可以再细化为：

`FETCHING_DIFF`、`RULE_ANALYSIS`、`LLM_REVIEW`、`COMMENTING`、`RETRYING`。

## 9. 数据表裁剪建议

MVP 建议保留四张表：

| 表 | 作用 |
| --- | --- |
| review_task | 审查任务主表，保存仓库、PR、commit sha、状态、风险等级 |
| changed_file | 保存 PR 改动文件元信息 |
| review_finding | 保存规则和 LLM 发现的问题 |
| github_comment | 保存评论 ID、body hash，避免重复刷屏 |

MVP Plus 再增加：

| 表 | 作用 |
| --- | --- |
| llm_review | 保存模型名、token、raw response、parsed json、错误信息 |
| rule_config | 保存规则开关和严重级别配置 |
| repo_config | 保存仓库级配置 |

## 10. 风险控制策略

### 10.1 LLM 降级

LLM 调用失败或 JSON 解析失败时，系统不应直接失败，而是：

1. 保存错误原因。
2. 使用规则引擎结果生成报告。
3. 在评论中标注：LLM review unavailable, rule-based review completed.
4. 任务状态设置为 `COMPLETED_WITH_WARNINGS`。

### 10.2 Diff 截断

默认最多传给 LLM 800 行 diff，并优先保留：

- Java 文件
- YAML 配置文件
- SQL migration 文件
- Controller
- Service
- Entity / Model
- 命中规则的文件

### 10.3 密钥保护

必须避免把疑似密钥原文写入日志、数据库、PR 评论和 LLM prompt。

处理方式：

- 日志脱敏。
- 评论脱敏。
- LLM prompt 中对疑似密钥值做 mask。
- 数据库只保存规则命中位置和摘要。

### 10.4 评论幂等

MVP 建议策略：

- `review_task` 使用 `owner + repo + pr_number + commit_sha` 唯一键。
- `github_comment` 保存 `task_id + body_hash`。
- 同一 commit 重复触发时跳过重复评论。

完整版策略：

- 保存 `comment_id`。
- 新审查结果生成后更新旧评论，而不是创建新评论。

## 11. Demo 设计

建议准备一个 Java Spring Boot 测试仓库，创建 3 个测试 PR。

| PR | 改动内容 | 预期命中 |
| --- | --- | --- |
| PR 1 | 修改 Controller，但不新增测试 | Controller 无测试 |
| PR 2 | 修改 Entity 字段，但不提供 migration SQL | Entity 无 migration |
| PR 3 | 新增疑似 secret、System.out.println 或 printStackTrace | 硬编码密钥 / 调试残留 |

Demo 展示顺序：

1. 创建测试 PR。
2. 手动触发或 Webhook 触发审查。
3. 展示 RabbitMQ 消息被消费。
4. 展示 MySQL 中任务状态变化。
5. 展示 GitHub PR 下的 RepoGuard Review 评论。
6. 展示 Vue Dashboard 中的任务详情。

## 12. 面试讲法

### 12.1 项目一句话

RepoGuard Agent 是一个面向 Java 项目的 AI PR 质量守门员，接入 GitHub PR 工作流，通过 RabbitMQ 异步执行 diff 拉取、规则引擎审查、LLM 语义分析和 PR 评论回写，帮助团队在合并前发现高风险变更和测试缺口。

### 12.2 简历 Bullet

基于 Spring Boot、Vue、MySQL、RabbitMQ、Spring AI 与 Spring AI Alibaba 实现 RepoGuard Agent MVP，接入 GitHub Pull Request API，通过异步任务流水线完成 diff 拉取、规则引擎审查、LLM 语义分析和 PR 评论回写，支持风险评级、测试缺口识别、幂等处理、失败降级和审查结果持久化。

### 12.3 可扩展讲点

- 为什么使用 RabbitMQ：避免 GitHub Webhook 阻塞，提升可靠性。
- 为什么规则引擎和 LLM 结合：规则提供稳定兜底，LLM 提供语义解释。
- 如何处理重复 webhook：基于 `repo + pr + commitSha` 做幂等。
- 如何处理 LLM 不稳定：JSON 解析失败降级为规则审查。
- 如何避免评论刷屏：保存 `comment_id` 或 `body_hash`。
- 如何控制成本：diff 截断、优先高风险文件、限制消费者并发。

## 13. 最终建议

RepoGuard Agent 值得做，但当前文档需要明确区分 MVP、MVP Plus 和 V1。

推荐版本边界：

| 版本 | 目标 |
| --- | --- |
| MVP | 跑通手动触发到 GitHub PR 评论的完整闭环 |
| MVP Plus | 增加 Webhook、简单重试/DLQ、Vue 页面完善、评论更新 |
| V1 | 接入静态分析、规则配置化、趋势统计、成本统计 |
| V2 | GitHub App、多仓库、多用户、团队规则模板 |

一周内最重要的不是完成所有功能，而是确保核心 Demo 稳定可信：

提交一个测试 PR 后，RepoGuard 能异步分析改动，并在 GitHub PR 下生成一条结构化审查评论。

