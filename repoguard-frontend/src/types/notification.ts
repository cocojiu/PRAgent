export interface NotificationBinding {
  id: number;
  name: string;
  provider: "DINGTALK" | "WECOM" | string;
  organization: string;
  repository: string;
  enabled: boolean;
  webhookUrl?: string;
  webhookUrlStatus?: "missing" | "configured" | "key_mismatch" | "decrypt_failed";
  secret?: string;
  secretStatus?: "missing" | "configured" | "key_mismatch" | "decrypt_failed";
  notifyReviewCompleted: boolean;
  notifyReviewFailed: boolean;
  notifyHumanReviewRequired: boolean;
  notifyGithubComment: boolean;
  status: string;
  lastCheckedAt?: string;
  lastError?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface NotificationBindingRequest {
  name: string;
  provider: string;
  organization: string;
  repository: string;
  enabled: boolean;
  webhookUrl?: string;
  secret?: string;
  notifyReviewCompleted: boolean;
  notifyReviewFailed: boolean;
  notifyHumanReviewRequired: boolean;
  notifyGithubComment: boolean;
}

export interface NotificationBindingStatusRequest {
  enabled: boolean;
}

export interface NotificationEvent {
  id: number;
  eventKey: string;
  eventType: string;
  taskId: number;
  batchId?: number;
  status: string;
  retryCount: number;
  nextRetryAt?: string;
  lastError?: string;
  deliverySummary?: NotificationDeliverySummary;
  createdAt?: string;
  updatedAt?: string;
}

export interface NotificationDeliverySummary {
  providers: string[];
  deliveryCount: number;
  failedDeliveryCount: number;
  latestDeliveryStatus?: string;
}

export interface NotificationDelivery {
  id: number;
  eventId: number;
  bindingId: number;
  taskId: number;
  provider: string;
  status: string;
  attemptCount: number;
  failureReason?: string;
  requestId?: string;
  sentAt?: string;
  createdAt?: string;
}

export type NotificationLevel = "danger" | "warning" | "success" | "info";

export interface NotificationItem {
  id: string;
  level: NotificationLevel;
  title: string;
  description: string;
  time: string;
  targetPath?: string;
  createdAt?: string;
}

export interface NotificationCenter {
  total: number;
  generatedAt: string;
  items: NotificationItem[];
}
