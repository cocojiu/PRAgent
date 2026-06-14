# RepoGuard Agent V24 企业级上线测试执行与优化报告

编制日期：2026-06-14  
测试分支：`codex/v24-demo-validation`  
测试提交：`d6e8b82`  
测试负责人：Codex  
测试性质：V24 面试演示版上线前本地验证、真实 PR 审查链路验证、旧版上线报告对比  
测试结论：**有条件通过**

## 1. 测试摘要

V24 主链路已完成端到端验证：后端全量测试通过、前端类型检查和构建通过、独立验证库 Flyway 可从空库迁移到 V24、V24 demo seed `9001-9004` 存在、真实 GitHub PR `#7` 可创建审查任务 `9005`，并完成 LLM 审查、PR 总评预览、首次 GitHub 回写和二次回写幂等验证。

本轮上线建议为：**允许部署到服务器 demo/staging，用于面试演示和个人真实仓库 PR 试运行；暂不建议直接作为生产级多用户服务开放**。主要原因是：V2 legacy 数据仍存在、异常 PR 重复提交出现 500、RabbitMQ 配置页展示状态与运行态不一致、GitHub 写回前置连接检查存在误报。

与旧版 `RepoGuard-Agent-上线测试执行与优化报告.md` 对比：

| 维度 | 旧报告 | V24 本轮实测 |
| --- | --- | --- |
| 后端自动化测试 | 66 tests | 209 tests |
| 前端 Node | Node 22.22.3 | Node 22.22.3 |
| 前端类型检查 | 通过 | 通过 |
| 前端构建 | 通过，有大 chunk warning | 通过，有 Rollup PURE annotation warning |
| 业务任务 | `535/536/537` | V24 demo `9001-9004` + 真实 PR 任务 `9005` |
| GitHub 回写 | 行评论 + 幂等 | PR 总评 + 幂等，URL：`https://github.com/cocojiu/PRAgent/pull/7#issuecomment-4701549014` |
| PR 列表 headSha | 旧报告列为问题 | V24 已返回 `headSha` |
| 认证授权 | 旧报告建议补充 | V24 已验证登录、Token、Admin Key |
| LLM 成本 | 旧报告未覆盖 | V24 已记录 token/cost |
| 分片/partial fallback | 旧报告未覆盖 | V24 seed 覆盖，真实 PR 未触发分片 |

## 2. 测试环境

| 项 | 实测值 |
| --- | --- |
| 操作系统 | Windows 11 |
| 后端端口 | `8081` |
| 数据库 | MySQL 8.0，本轮独立库 `repoguard_v24_validation` |
| RabbitMQ | `localhost:5672`，运行态连接成功 |
| Java/JDK | Oracle JDK `26.0.1` |
| Maven | Apache Maven `3.9.16` |
| Node | `v22.22.3` |
| GitHub 仓库 | `cocojiu/PRAgent` |
| 测试 PR | `https://github.com/cocojiu/PRAgent/pull/7` |
| LLM provider/model | `dashscope` / `mimo-v2.5-pro` |
| V24 demo seed | 已使用，`9001-9004` 存在 |

## 3. 自动化测试结果

| 测试编号 | 测试域 | 测试项 | 测试时间 | 测试标准 | 测试命令/步骤 | 实际结果 | 结论 | 严重等级 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AUTO-001 | 自动化 | 后端全量测试 | 2026-06-14 18:53:37 - 18:53:55，18.171s | 全量单元测试 0 failures / 0 errors | `mvn test` | 209 tests，0 failures，0 errors，0 skipped | 通过 | Minor | Maven 输出 |
| AUTO-002 | 自动化 | 前端类型检查 | 2026-06-14 18:42:10 - 18:42:15，5.286s | TypeScript 编译通过 | `vue-tsc -b` | 通过 | 通过 | Minor | 命令输出 |
| AUTO-003 | 自动化 | 前端生产构建 | 2026-06-14 18:42:23 - 18:42:37，14.191s | Vite 构建成功 | `vite build` | 通过，存在 `@vueuse/core` PURE annotation warning | 通过 | Minor | Vite 输出 |

## 4. 数据库与 V24 Seed 验证

| 测试编号 | 测试域 | 测试项 | 测试时间 | 测试标准 | 测试命令/步骤 | 实际结果 | 结论 | 严重等级 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DB-001 | 数据库 | 空库 Flyway 迁移 | 2026-06-14 18:54:26 - 18:54:36，10.311s | 从空库启动后迁移到 V24 | 后端连接 `repoguard_v24_validation` 启动 | Flyway 应用 24 个 migration，当前版本 V24 | 通过 | Minor | 日志 `Successfully applied 24 migrations` |
| DB-002 | 数据库 | V24 demo 任务 | 2026-06-14 19:08 | `9001-9004` 全部存在 | SQL 查询 `review_task` | `v24_task_count=4` | 通过 | Minor | MySQL 查询 |
| DB-003 | 数据库 | V2 legacy 数据 | 2026-06-14 19:08 | 若未清理，报告明确标记 legacy | SQL 查询 `505-512` | `legacy_v2_task_count=8`，仍残留 | 通过但需标记 | Major | MySQL 查询 |
| DB-004 | 数据库 | Finding source 覆盖 | 2026-06-14 19:08 | V24 seed 覆盖 `LLM`、`RULE`、`LLM+RULE` | SQL 查询 `review_finding` | V24 seed 中存在 `LLM`、`RULE`、`LLM+RULE` | 通过 | Minor | MySQL 查询 |
| DB-005 | 数据库 | PR 总评回写记录 | 2026-06-14 19:08 | 支持 `finding_id = null` | SQL 查询 `github_comment_publication` | `9001/9002/9005` 均有 `pull_request` 类型总评记录 | 通过 | Minor | MySQL 查询 |

V24 demo 任务概要：

| 任务 | 状态 | 风险 | LLM 状态 | Parse 状态 | Token/Cost |
| --- | --- | --- | --- | --- | --- |
| `9001` | `PENDING_HUMAN_REVIEW` | `HIGH` | `COMPLETED` | `PARTIAL_FALLBACK` | 22140 / 0.012846 |
| `9002` | `COMPLETED` | `LOW` | `COMPLETED` | `PARSED` | 6180 / 0.002312 |
| `9003` | `COMPLETED` | `MEDIUM` | `FALLBACK` | `FALLBACK` | 未记录 |
| `9004` | `COMPLETED` | `INFO` | `COMPLETED` | `PARSED` | 3530 / 0.001128 |
| `9005` | `COMPLETED` | `LOW` | `COMPLETED` | `parsed` | 2726 / 0.004610 |

## 5. 接口测试明细

| 测试编号 | 测试域 | 接口 | 测试标准 | 状态码 | 响应时间 | 实际结果 | 结论 |
| --- | --- | --- | --- | ---: | ---: | --- | --- |
| API-001 | 接口 | `GET /actuator/health` | 返回 `UP` | 200 | 约 5ms | 健康检查通过 | 通过 |
| API-002 | 接口 | `POST /api/v1/auth/login` | 返回 accessToken | 200 | 约 70ms | 用户 `validation_admin` 登录成功 | 通过 |
| API-003 | 接口 | `GET /api/v1/auth/me` | 返回当前用户 | 200 | 约 8ms | `role=ADMIN` | 通过 |
| API-004 | 接口 | `GET /api/v1/reviews/github/pull-requests` | 返回测试 PR 且含 `headSha` | 200 | 约 809ms | 返回 PR `#7`，`headSha=d6e8b82...` | 通过 |
| API-005 | 接口 | `POST /api/v1/reviews/manual` | 创建审查任务 | 200 | 约 50ms | 返回任务 `9005`，`queued` | 通过 |
| API-006 | 接口 | `GET /api/v1/reviews/9005/status` | 可轮询到终态 | 200 | 6-8ms | 18 次轮询后 `completed` | 通过 |
| API-007 | 接口 | `GET /api/v1/reviews/9005` | 返回详情、总评、成本、MQ 状态 | 200 | 32ms | 详情完整 | 通过 |
| API-008 | 接口 | `GET /api/v1/reviews/9005/github-comments/preview` | 生成可回写总评 | 200 | 18ms | `commentableCount=1`，含 PR 总评 | 通过 |
| API-009 | 接口 | `POST /api/v1/reviews/9005/github-comments` | 首次回写成功 | 200 | 1007ms | `succeededCount=1` | 通过 |
| API-010 | 接口 | `POST /api/v1/reviews/9005/github-comments` | 二次回写幂等 | 200 | 51ms | `skippedCount=1`，`already_published` | 通过 |
| API-011 | 接口 | `GET /api/v1/reviews/9005/github-comments/publications` | 返回回写历史 | 200 | 92ms | 返回 2 个批次 | 通过 |
| API-012 | 接口 | `GET /api/v1/dashboard/overview?llmTrendDays=7` | Dashboard 可用 | 200 | 17ms | 返回趋势、分布、规则命中 | 通过 |
| API-013 | 接口 | `GET /api/v1/dashboard/overview?llmTrendDays=30` | Dashboard 可用 | 200 | 11ms | 返回合理窗口数据 | 通过 |
| API-014 | 接口 | `GET /api/v1/dashboard/overview?llmTrendDays=90` | Dashboard 可用 | 200 | 11ms | 返回合理窗口数据 | 通过 |
| API-015 | 接口 | `GET /api/v1/message-queue/health` | 返回 MQ 运行态 | 200 | 10ms | `runtimeConnectionStatus=CONNECTED` | 通过 |
| API-016 | 接口 | `GET /api/v1/config/integrations/github` | Token 脱敏 | 200 | 5ms | `token=****PB0Y` | 通过 |
| API-017 | 接口 | `GET /api/v1/config/review-policy` | API Key 脱敏 | 200 | 3ms | `apiKey=****7g5c` | 通过 |

## 6. 业务流程测试

| 测试编号 | 测试域 | 测试项 | 测试时间 | 测试标准 | 实际结果 | 结论 | 严重等级 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BUS-001 | 业务 | V24 demo 任务详情检查 | 2026-06-14 19:08 | `9001-9004` 支持演示数据 | V24 任务、finding、PR 总评、partial fallback 均存在 | 通过 | Minor | MySQL 查询 |
| BUS-002 | 业务 | 新建测试 PR 审查任务 | 2026-06-14 19:05:22 | PR `#7` 可被拉取并建任务 | 创建任务 `9005` | 通过 | Minor | API 返回 |
| BUS-003 | 业务 | LLM + 规则混合审查 | 2026-06-14 19:05:22 - 19:05:58 | LLM 完成，规则参与 | `promptSummary` 显示 `rulesApplied=true; ruleFindings=0; mergedFindings=0` | 通过 | Minor | 任务详情 |
| BUS-004 | 业务 | 分片审查 | 2026-06-14 19:05:58 | 小 PR 可不触发分片；V24 seed 覆盖分片样例 | 真实 PR `chunkedReview.enabled=false`；V24 seed 含 chunk/fallback 数据 | 通过 | Minor | 详情 + seed SQL |
| BUS-005 | 业务 | Finding 去重 | 2026-06-14 19:05:58 | 无重复 finding/评论 | 真实 PR 无 finding，仅总评；二次回写未重复发评论 | 通过 | Minor | 发布历史 |
| BUS-006 | 业务 | PR 总评展示 | 2026-06-14 19:05:58 | 详情页和预览包含 PR 总评 | `prSummary` 存在，preview `targetType=pull_request` | 通过 | Minor | API 返回 |
| BUS-007 | 业务 | GitHub 评论预览与一次回写 | 2026-06-14 19:05:58 - 19:05:59 | 预览可发布，首次回写成功 | 评论 URL 已生成 | 通过 | Minor | `#issuecomment-4701549014` |
| BUS-008 | 业务 | 二次回写幂等验证 | 2026-06-14 19:05:59 | 不重复刷评论 | `already_published`，`skippedCount=1` | 通过 | Minor | 发布历史 |

真实 PR 任务 `9005` 结果：

| 指标 | 值 |
| --- | --- |
| PR | `https://github.com/cocojiu/PRAgent/pull/7` |
| 状态 | `completed` |
| 风险 | `low` |
| 变更文件 | 5 |
| Findings | 0 |
| LLM 耗时 | 35601ms |
| Prompt tokens | 1208 |
| Completion tokens | 1518 |
| Total tokens | 2726 |
| 估算成本 | 0.004610 |
| RabbitMQ | `deliveryCount=1`，`consumeStatus=confirmed` |

## 7. 安全与权限测试

| 测试编号 | 测试域 | 测试项 | 测试标准 | 实际结果 | 结论 | 严重等级 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-001 | 安全 | 注册/登录 | 可创建验证用户并登录 | `validation_admin` 注册/登录成功，角色 `ADMIN` | 通过 | Minor |
| SEC-002 | 安全 | Token 保护 | 未带 Token 访问业务 API 返回 401 | `GET /api/v1/reviews/9005` 返回 401 | 通过 | Minor |
| SEC-003 | 安全 | Admin Key 保护 | 管理员写接口缺少 Admin Key 返回 401 | `POST /api/v1/reviews/manual` 返回 401 | 通过 | Minor |
| SEC-004 | 安全 | GitHub token 脱敏 | 不返回明文 token | `token=****PB0Y` | 通过 | Minor |
| SEC-005 | 安全 | LLM key 脱敏 | 不返回明文 API key | `apiKey=****7g5c` | 通过 | Minor |
| SEC-006 | 安全 | 生产密钥默认值检查 | prod profile 不允许默认 key | 代码含 prod 校验，本轮未以 prod profile 启动 | 跳过 | Major |

## 8. 异常与恢复测试

| 测试编号 | 测试域 | 测试项 | 测试标准 | 实际结果 | 结论 | 严重等级 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ERR-001 | 异常 | 不存在 PR | 应返回可读 4xx 或创建失败任务 | 第一次创建 `9006` 后 worker 快速消费；重复提交触发唯一键冲突并返回 500 | 失败 | Major | 日志 `Duplicate entry 'cocojiu-PRAgent-999999-pending'` |
| ERR-002 | 异常 | GitHub 连接前置检查 | 健康状态应与实际回写能力一致 | Preview `writebackCheck.status=connection_failed`，但首次回写成功 | 失败 | Major | Preview lastError 404 + publish success |
| ERR-003 | 异常 | RabbitMQ 配置状态 | 配置页状态应与运行态一致 | activeConfig status 为 `FAILED`，runtimeConnectionStatus 为 `CONNECTED` | 失败 | Major | `GET /api/v1/message-queue/health` |
| ERR-004 | 异常 | LLM 超时/key 错误 | 需通过专门错误 key 验证 | 本轮使用真实 LLM 成功，未破坏配置做错误 key 测试 | 跳过 | Major | 不污染验证配置 |

## 9. 性能与响应时间统计

| 类型 | 指标 | 实测 |
| --- | --- | --- |
| 后端测试 | `mvn test` | 18.171s |
| 前端类型检查 | `vue-tsc -b` | 5.286s |
| 前端构建 | `vite build` | 14.191s |
| 后端启动 | Spring Boot started | 3.679s，进程运行 4.045s |
| Flyway 校验 | 24 migrations | 0.037s |
| PR 列表 | `GET /api/v1/reviews/github/pull-requests` | 约 809ms |
| 任务创建 | `POST /api/v1/reviews/manual` | 约 50ms |
| 状态轮询 | `GET /api/v1/reviews/9005/status` | 6-8ms |
| 任务详情 | `GET /api/v1/reviews/9005` | 32ms |
| GitHub 预览 | `GET /github-comments/preview` | 18ms |
| 首次回写 | `POST /github-comments` | 1007ms |
| 二次幂等 | `POST /github-comments` | 51ms |
| LLM 调用 | DashScope `mimo-v2.5-pro` | 35601ms，2726 tokens，0.004610 |
| Dashboard | 7/30/90 天窗口 | 11-17ms |

## 10. 旧版报告对比

| 对比项 | 旧版状态 | V24 当前状态 | 结论 |
| --- | --- | --- | --- |
| 自动化测试数量 | 66 tests | 209 tests | 覆盖显著增强 |
| 前端构建 | Node 22.22.3 构建通过 | Node 22.22.3 构建通过 | 持平 |
| 业务任务 | `535/536/537` | `9001-9005` | V24 demo 数据和真实 PR 均覆盖 |
| GitHub 回写 | 行评论回写 + 幂等 | PR 总评回写 + 幂等 | 新能力已覆盖 |
| MySQL/RabbitMQ 配置一致性 | 遗留问题 | RabbitMQ 仍有配置态/运行态不一致 | 未完全修复 |
| PR 列表 `headSha` | 遗留问题 | `headSha` 已返回 | 已修复 |
| 大 chunk warning | 遗留问题 | 本轮 Vite 无大 chunk warning，但有 PURE annotation warning | 部分改善 |
| 后台认证授权 | 建议补充 | Token + Admin Key 已验证 | 已增强 |
| 详情轮询成本 | 建议优化 | 已有轻量 status 接口，轮询 6-8ms | 已改善 |
| 权限审计 | 旧版未覆盖 | 登录、Token、Admin Key 覆盖 | 已覆盖基础项 |
| 混合审查 | 旧版未覆盖 | V24 seed + 真实任务 promptSummary 覆盖 | 已覆盖 |
| 分片审查 | 旧版未覆盖 | V24 seed 覆盖，真实小 PR 未触发 | 条件覆盖 |
| partial fallback | 旧版未覆盖 | `9001` 覆盖 | 已覆盖 |
| 成本统计 | 旧版未覆盖 | `9005` token/cost 实测 | 已覆盖 |
| demo seed 数据 | 旧版未覆盖 | `9001-9004` 可用 | 已覆盖 |

## 11. 问题清单

| 编号 | 严重等级 | 问题 | 证据 | 影响 | 建议 | 是否阻塞上线 |
| --- | --- | --- | --- | --- | --- | --- |
| ISS-V24-001 | Major | V2 legacy 数据 `505-512` 仍存在 | SQL `legacy_v2_task_count=8` | Dashboard/列表可能混入旧演示数据 | 新增 V25 清理或前端默认筛选 V24 demo 数据 | 不阻塞 demo，阻塞干净演示库 |
| ISS-V24-002 | Major | 不存在 PR 重复提交返回 500 | `Duplicate entry 'cocojiu-PRAgent-999999-pending'` | 异常体验不符合企业级错误标准 | 创建任务前按 `organization/repository/prNumber/commit` 做幂等复用，或返回 409/400 | 不阻塞主链路 |
| ISS-V24-003 | Major | GitHub preview 写回检查误报 `connection_failed` | Preview lastError 404，但 publish 成功 | 演示时可能让面试官误判写回不可用 | 区分列表文件检查与 issue comment 权限检查 | 不阻塞主链路 |
| ISS-V24-004 | Major | RabbitMQ 配置态和运行态不一致 | activeConfig `FAILED`，runtime `CONNECTED` | 运维页面可读性差 | 配置页展示“保存配置状态”和“当前运行连接状态”双状态 | 不阻塞主链路 |
| ISS-V24-005 | Minor | Vite 构建存在第三方 PURE annotation warning | `@vueuse/core` warning | 不影响构建，但影响构建日志清洁度 | 后续升级依赖或忽略已知 warning | 不阻塞 |
| ISS-V24-006 | Minor | 真实 PR 未触发 chunked review | `chunkedReview.enabled=false` | 分片能力仅通过 seed 验证 | 准备大 PR 或专用 fixture 触发 chunked review | 不阻塞 demo |

## 12. 上线结论

结论：**有条件通过，建议上线到服务器 demo/staging**。

允许事项：

- 允许部署到服务器作为面试演示环境。
- 允许接入个人真实仓库 PR，建议先限制为你自己的仓库和测试 PR。
- 允许演示 V24 demo 数据、LLM 成本、PR 总评、GitHub 回写、幂等回写、Dashboard 趋势。

上线前建议先处理：

- 新增 V25 清理或隔离 V2 legacy 数据，让演示数据只展示 V24。
- 修复不存在 PR 重复提交返回 500 的异常处理。
- 修正 GitHub preview 写回检查误报。
- 调整 RabbitMQ 配置页状态表达，区分配置测试失败和运行态连接成功。

生产化前仍需补充：

- prod profile 下完整密钥、CORS、域名、HTTPS、日志脱敏、备份恢复验证。
- LLM 错误 key、超时、限流、成本上限的破坏性测试。
- 大 PR fixture 触发 chunked review 的真实链路测试。
- 多用户 RBAC、审计日志、权限回归测试。
