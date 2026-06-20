export interface BaseSettings {
  systemName: string;
  language: string;
  timezone: string;
  retentionDays: number;
}

export interface ReviewPolicySettings {
  maxDiffLines: number;
  llmTimeoutSeconds: number;
  workerConcurrency: number;
  autoComment: boolean;
  autoRetry: boolean;
}

export interface NotificationSettings {
  githubComment: boolean;
  highRiskPr: boolean;
  failedTask: boolean;
  email: string;
}

export interface SecuritySettings {
  webhookSignature: boolean;
  secretMasking: boolean;
  publicRepoAllowed: boolean;
  tokenTtlDays: number;
}

export interface SettingLog {
  time: string;
  operator: string;
  action: string;
  status: string;
}

export interface SystemSettings {
  base: BaseSettings;
  policy: ReviewPolicySettings;
  notification: NotificationSettings;
  security: SecuritySettings;
  logs: SettingLog[];
}

export type SystemSettingsRequest = Omit<SystemSettings, "logs">;
