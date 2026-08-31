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
  occurredAt?: string;
  operator: string;
  action: string;
  status: string;
}

export type SecretReEncryptionStatus =
  | "RE_ENCRYPTED"
  | "WOULD_RE_ENCRYPT"
  | "SKIPPED_EMPTY"
  | "SKIPPED_TARGET_KEY"
  | "KEY_MISMATCH"
  | "DECRYPT_FAILED"
  | "FAILED";

export type SecretReEncryptionJobStatus =
  | "PENDING"
  | "RUNNING"
  | "RETRY_WAIT"
  | "PAUSED"
  | "COMPLETED"
  | "COMPLETED_WITH_ERRORS"
  | "FAILED";

export interface SecretReEncryptionRequest {
  sourceEncryptionKey: string;
  sourceKeyId?: string;
  targetEncryptionKey: string;
  targetKeyId: string;
  execute?: boolean;
  confirmText?: string;
}

export interface SecretReEncryptionItem {
  tableName: string;
  recordId: number;
  fieldName: string;
  provider?: string;
  sourceFormat?: string;
  sourceKeyId?: string;
  targetKeyId?: string;
  status: SecretReEncryptionStatus;
  failureReason?: string;
  message?: string;
}

export interface SecretReEncryptionJob {
  id: number;
  executed: boolean;
  status: SecretReEncryptionJobStatus;
  sourceKeyId: string;
  targetKeyId: string;
  currentTable: string;
  checkpointId: number;
  batchSize: number;
  scannedCount: number;
  reEncryptedCount: number;
  skippedCount: number;
  failedCount: number;
  retryCount: number;
  nextRetryAt?: string;
  lastFailureReason?: string;
  createdByUsername?: string;
  createdAt?: string;
  updatedAt?: string;
  completedAt?: string;
}

export interface SystemSettings {
  base: BaseSettings;
  policy: ReviewPolicySettings;
  notification: NotificationSettings;
  security: SecuritySettings;
  logs: SettingLog[];
}

export type SystemSettingsRequest = Omit<SystemSettings, "logs">;
