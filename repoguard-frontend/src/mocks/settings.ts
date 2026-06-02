export const baseSettings = {
  systemName: "RepoGuard Agent",
  language: "中文",
  timezone: "Asia/Shanghai",
  retentionDays: 90
};

export const reviewPolicySettings = {
  maxDiffLines: 800,
  llmTimeoutSeconds: 60,
  workerConcurrency: 2,
  autoComment: true,
  autoRetry: true
};

export const notificationSettings = {
  githubComment: true,
  highRiskPr: true,
  failedTask: true,
  email: "ops@repoguard.dev"
};

export const securitySettings = {
  webhookSignature: true,
  secretMasking: true,
  publicRepoAllowed: false,
  tokenTtlDays: 30
};

export const settingLogs = [
  { time: "2025-05-30 15:20:11", operator: "admin", action: "更新 LLM 超时时间为 60 秒", status: "成功" },
  { time: "2025-05-30 14:08:42", operator: "admin", action: "开启 Webhook 签名校验", status: "成功" },
  { time: "2025-05-29 19:31:05", operator: "system", action: "刷新系统健康检查配置", status: "成功" }
];

