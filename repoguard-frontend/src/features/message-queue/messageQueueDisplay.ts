import type { MessageQueueExceptionTask } from "@/types";

export const MESSAGE_QUEUE_STATUS_OPTIONS = [
  { label: "发布失败", value: "PUBLISH_FAILED" },
  { label: "执行超时", value: "EXECUTION_TIMEOUT" },
  { label: "重入队中", value: "REQUEUE_PENDING" },
  { label: "补偿中", value: "PUBLISH_CLAIMED" },
  { label: "重试耗尽", value: "RETRY_EXHAUSTED" },
  { label: "DLQ", value: "DLQ" }
] as const;

type MessageQueueStatus = MessageQueueExceptionTask["status"];

const STATUS_TEXT: Record<string, string> = {
  PUBLISH_FAILED: "发布失败",
  EXECUTION_TIMEOUT: "执行超时",
  REQUEUE_PENDING: "重入队中",
  PUBLISH_CLAIMED: "补偿中",
  RETRY_EXHAUSTED: "重试耗尽",
  DLQ: "DLQ"
};

const STATUS_CLASS: Record<string, string> = {
  PUBLISH_FAILED: "warning",
  EXECUTION_TIMEOUT: "danger",
  REQUEUE_PENDING: "processing",
  PUBLISH_CLAIMED: "processing",
  RETRY_EXHAUSTED: "danger",
  DLQ: "danger"
};

const METRIC_LABELS: Record<string, string> = {
  "Publish succeeded": "发布成功",
  "Publish failed": "发布失败",
  "Execution timeout": "执行超时",
  "Requeue pending": "重入队中",
  Compensating: "补偿中",
  "DLQ backlog": "DLQ积压"
};

const METRIC_NOTES: Record<string, string> = {
  "Current active config": "当前生效配置",
  "Waiting for compensation": "等待补偿",
  "Review lease expired": "执行租约已过期",
  "Execution recovery publishing": "执行恢复发布中",
  "Claimed by workers": "Worker已抢占",
  "Database observed status": "数据库观测状态"
};

export const messageQueueStatusText = (status: MessageQueueStatus) => STATUS_TEXT[status] ?? status;

export const messageQueueStatusClass = (status: MessageQueueStatus) => STATUS_CLASS[status] ?? "pending";

export const canRequeueMessageQueueStatus = (status: MessageQueueStatus) =>
  status === "PUBLISH_FAILED" || status === "EXECUTION_TIMEOUT";

export const messageQueueRequeueTooltip = (status: MessageQueueStatus) => {
  if (status === "PUBLISH_FAILED") {
    return "将发布失败任务重新发送到 RabbitMQ";
  }
  if (status === "EXECUTION_TIMEOUT") {
    return "将执行超时任务重新发送到 RabbitMQ";
  }
  if (status === "REQUEUE_PENDING") {
    return "执行超时任务正在恢复发布中";
  }
  if (status === "PUBLISH_CLAIMED") {
    return "发布补偿任务已被 Worker 抢占";
  }
  return "当前状态不支持重新入队";
};

export const messageQueueMetricLabel = (label: string) => METRIC_LABELS[label] ?? label;

export const messageQueueMetricNote = (note: string) => METRIC_NOTES[note] ?? note;
