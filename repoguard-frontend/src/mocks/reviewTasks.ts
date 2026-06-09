import type { ChangedFile, MissingTest, ReviewFinding, ReviewTask, TimelineItem } from "@/types";

export const reviewTasks: ReviewTask[] = [
  {
    id: 512,
    prNumber: 512,
    title: "新增用户导出接口",
    repository: "spring-boot-demo",
    organization: "repo-guard-demo",
    commit: "a1b2c3d",
    branch: "main",
    status: "completed",
    riskLevel: "high",
    mqRetries: 0,
    llmStatus: "completed",
    source: "manual_input",
    triggerSource: "manual_input",
    createdAt: "2025-05-31 14:32:21",
    duration: "2 分 48 秒"
  },
  {
    id: 511,
    prNumber: 511,
    title: "修复登录校验问题",
    repository: "auth-service",
    organization: "monorepo",
    commit: "d4e5f6q",
    branch: "main",
    status: "reviewing",
    riskLevel: "medium",
    mqRetries: 1,
    llmStatus: "reviewing",
    source: "github_pr_picker",
    triggerSource: "github_pr_picker",
    createdAt: "2025-05-30 14:21:07",
    duration: "1 分 12 秒"
  },
  {
    id: 510,
    prNumber: 510,
    title: "优化缓存策略",
    repository: "common-lib",
    organization: "monorepo",
    commit: "h7i8j9k",
    branch: "main",
    status: "completed",
    riskLevel: "low",
    mqRetries: 0,
    llmStatus: "completed",
    source: "manual_input",
    triggerSource: "manual_input",
    createdAt: "2025-05-30 13:58:42",
    duration: "2 分 03 秒"
  },
  {
    id: 509,
    prNumber: 509,
    title: "更新依赖版本",
    repository: "gateway",
    organization: "monorepo",
    commit: "11m2n3o",
    branch: "main",
    status: "completed",
    riskLevel: "medium",
    mqRetries: 0,
    llmStatus: "completed",
    source: "github_pr_picker",
    triggerSource: "github_pr_picker",
    createdAt: "2025-05-30 13:37:11",
    duration: "1 分 50 秒"
  },
  {
    id: 508,
    prNumber: 508,
    title: "移除调试日志",
    repository: "order-service",
    organization: "monorepo",
    commit: "p4q5r6s",
    branch: "main",
    status: "failed",
    riskLevel: "high",
    mqRetries: 3,
    llmStatus: "failed",
    source: "manual_input",
    triggerSource: "manual_input",
    createdAt: "2025-05-30 12:54:33",
    duration: "3 分 26 秒"
  },
  {
    id: 507,
    prNumber: 507,
    title: "新增订单导出功能",
    repository: "order-service",
    organization: "monorepo",
    commit: "t7u8v9w",
    branch: "main",
    status: "completed",
    riskLevel: "low",
    mqRetries: 0,
    llmStatus: "completed",
    source: "github_pr_picker",
    triggerSource: "existing_reused",
    createdAt: "2025-05-30 11:22:09",
    duration: "2 分 11 秒"
  },
  {
    id: 506,
    prNumber: 506,
    title: "完善异常处理",
    repository: "payment-service",
    organization: "monorepo",
    commit: "x1y2z3a",
    branch: "main",
    status: "reviewing",
    riskLevel: "medium",
    mqRetries: 1,
    llmStatus: "reviewing",
    source: "manual_input",
    triggerSource: "manual_input",
    createdAt: "2025-05-30 10:15:55",
    duration: "1 分 38 秒"
  },
  {
    id: 505,
    prNumber: 505,
    title: "修复并发问题",
    repository: "inventory-service",
    organization: "monorepo",
    commit: "b4c5d6e",
    branch: "main",
    status: "failed",
    riskLevel: "high",
    mqRetries: 2,
    llmStatus: "failed",
    source: "github_pr_picker",
    triggerSource: "github_pr_picker",
    createdAt: "2025-05-30 09:45:31",
    duration: "4 分 02 秒"
  }
];

export const taskMetrics = [
  { label: "本周审查", value: "128", trend: "15.6%", trendType: "up", color: "blue" },
  { label: "高风险 PR", value: "23", trend: "27.8%", trendType: "up-danger", color: "red" },
  { label: "失败任务", value: "7", trend: "12.5%", trendType: "down", color: "orange" },
  { label: "平均耗时", value: "3 分 48 秒", trend: "8.3%", trendType: "down", color: "green" }
];

export const reviewFindings: ReviewFinding[] = [
  {
    severity: "high",
    file: "src/main/java/com/demo/controller/ExportController.java",
    line: 45,
    message: "硬编码的 AccessKey 可能导致密钥泄露",
    recommendation: "将 AccessKey 和 SecretKey 提取到配置文件或使用 AK/SK 管理服务"
  },
  {
    severity: "high",
    file: "src/main/java/com/demo/service/ExportService.java",
    line: 78,
    message: "直接打印敏感信息到日志",
    recommendation: "使用脱敏处理或避免打印敏感信息"
  },
  {
    severity: "medium",
    file: "src/main/java/com/demo/controller/ExportController.java",
    line: 61,
    message: "缺少权限校验",
    recommendation: "建议在接口中增加权限校验逻辑"
  },
  {
    severity: "medium",
    file: "src/main/java/com/demo/service/ExportService.java",
    line: 132,
    message: "捕获泛型异常 Exception",
    recommendation: "捕获更具体的异常类型并做针对性处理"
  },
  {
    severity: "low",
    file: "src/main/java/com/demo/util/ExportUtil.java",
    line: 22,
    message: "使用了 System.out.println",
    recommendation: "使用日志框架替代 System.out"
  }
];

export const missingTests: MissingTest[] = [
  {
    file: "src/main/java/com/demo/controller/ExportController.java",
    method: "ExportController#export",
    type: "单元测试",
    suggestion: "为接口方法添加单元测试，覆盖正常和异常场景"
  },
  {
    file: "src/main/java/com/demo/service/ExportService.java",
    method: "ExportService#exportUsers",
    type: "单元测试",
    suggestion: "建议对核心业务逻辑进行单元测试覆盖"
  }
];

export const changedFiles: ChangedFile[] = [
  { path: "src/main/java/com/demo/controller/ExportController.java", changeType: "M", additions: 32, deletions: 6 },
  { path: "src/main/java/com/demo/service/ExportService.java", changeType: "M", additions: 87, deletions: 12 },
  { path: "src/main/java/com/demo/util/ExportUtil.java", changeType: "A", additions: 45, deletions: 0 },
  { path: "src/main/resources/application.yml", changeType: "M", additions: 4, deletions: 1 }
];

export const reviewTimeline: TimelineItem[] = [
  { label: "待处理", time: "14:32:22", status: "done" },
  { label: "已入队", time: "14:32:23", status: "done" },
  { label: "拉取 Diff", time: "14:32:28", status: "done" },
  { label: "规则分析", time: "14:32:45", status: "done" },
  { label: "LLM 审查", time: "14:33:32", status: "done" },
  { label: "评论回写", time: "14:34:58", status: "done" },
  { label: "已完成", time: "14:35:10", status: "done" }
];

export const selectedTask: ReviewTask = { ...reviewTasks[0] };
