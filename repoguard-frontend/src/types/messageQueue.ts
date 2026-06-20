import type { MetricColor } from "./shared";

export interface ActiveRabbitMqConfig {
  provider: string;
  status: string;
  runtimeConnectionStatus: string;
  baseUrl?: string;
  username?: string;
  virtualHost?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
  configVersion: string;
  switchNotice: string;
}

export interface RabbitMqTopology {
  exchange: string;
  queue: string;
  routingKey: string;
  deadLetterExchange: string;
  deadLetterQueue: string;
  deadLetterRoutingKey: string;
}

export interface MessageQueueMetric {
  label: string;
  value: string;
  note: string;
  noteClass?: string;
  color: MetricColor;
}

export interface RetryCompensationStatus {
  maxAttempts: number;
  intervalMs: number;
  batchSize: number;
  leaseMs: number;
  claimedTaskCount: number;
  latestSuccessAt?: string;
  latestFailureReason?: string;
}

export interface MessageQueueExceptionTask {
  taskId: number;
  organization?: string;
  repository?: string;
  prNumber?: number;
  status: "PUBLISH_FAILED" | "PUBLISH_CLAIMED" | "RETRY_EXHAUSTED" | "DLQ" | string;
  publishAttempts?: number;
  nextRetryAt?: string;
  claimedBy?: string;
  claimedAt?: string;
  lastError?: string;
}

export interface MessageQueueHealth {
  activeConfig: ActiveRabbitMqConfig;
  topology: RabbitMqTopology;
  metrics: MessageQueueMetric[];
  retryCompensation: RetryCompensationStatus;
  exceptionTasks: MessageQueueExceptionTask[];
  generatedAt: string;
  dataSource: string;
}

export interface MessageQueueRequeueResponse {
  taskId: number;
  status: "queued" | "publish_failed" | string;
  message: string;
  publishAttempts: number;
}
