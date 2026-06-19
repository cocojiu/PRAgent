import { CheckCircle2, Mail, MessageCircle, Send } from "lucide-vue-next";
import type { NotificationEvent } from "@/types";

const FAILED_EVENT_STATUSES = ["PUBLISH_FAILED", "DELIVERY_FAILED", "FAILED", "DEAD"];
const RETRYABLE_EVENT_STATUSES = ["PUBLISH_FAILED", "DELIVERY_FAILED", "FAILED", "DEAD", "PENDING"];
const RETRY_PENDING_EVENT_STATUSES = ["PENDING", "PUBLISH_FAILED", "DELIVERY_FAILED"];

const normalizeStatus = (status?: string) => status?.toUpperCase() ?? "";

export const isFailedNotificationEvent = (status?: string) =>
  FAILED_EVENT_STATUSES.includes(normalizeStatus(status));

export const canRetryNotificationEvent = (status?: string) =>
  RETRYABLE_EVENT_STATUSES.includes(normalizeStatus(status));

export const isRetryPendingNotificationEvent = (status?: string) =>
  RETRY_PENDING_EVENT_STATUSES.includes(normalizeStatus(status));

export const notificationStatusClass = (status: string) => {
  const normalized = normalizeStatus(status);
  if (["DELIVERED", "SUCCESS", "PUBLISHED"].includes(normalized)) {
    return "success";
  }
  if (["PENDING", "DELIVERING"].includes(normalized)) {
    return "processing";
  }
  if (FAILED_EVENT_STATUSES.includes(normalized)) {
    return "danger";
  }
  if (normalized === "SKIPPED") {
    return "warning";
  }
  return "pending";
};

export const notificationStatusText = (status: string) => {
  const normalized = normalizeStatus(status);
  if (["DELIVERED", "SUCCESS"].includes(normalized)) {
    return "已送达";
  }
  if (["PUBLISHED", "DELIVERING"].includes(normalized)) {
    return "待投递";
  }
  if (FAILED_EVENT_STATUSES.includes(normalized)) {
    return "失败";
  }
  if (normalized === "PENDING") {
    return "待重试";
  }
  if (normalized === "SKIPPED") {
    return "已跳过";
  }
  return status || "-";
};

export const eventTypeText = (type: string) => {
  const map: Record<string, string> = {
    REVIEW_COMPLETED: "审查完成",
    REVIEW_FAILED: "审查失败",
    HUMAN_REVIEW_REQUIRED: "需要人工复核",
    GITHUB_COMMENT_PUBLISHED: "GitHub 回写完成"
  };
  return map[type] ?? type;
};

export const deliveryCountText = (event: NotificationEvent) => {
  const count = event.deliverySummary?.deliveryCount ?? 0;
  return count > 0 ? `${count} 次` : "未投递";
};

export const providerText = (provider: string) => {
  if (provider === "DINGTALK") {
    return "钉钉";
  }
  if (provider === "WECOM") {
    return "企业微信";
  }
  if (provider === "EMAIL") {
    return "邮件";
  }
  return provider || "-";
};

export const channelText = (event: NotificationEvent) => {
  const providers = event.deliverySummary?.providers ?? [];
  if (!providers.length) {
    return "-";
  }
  return providers.map(providerText).join(" / ");
};

export const channelIcon = (event: NotificationEvent) => {
  const channel = event.deliverySummary?.providers?.[0] ? providerText(event.deliverySummary.providers[0]) : "";
  if (channel === "邮件") {
    return Mail;
  }
  if (channel === "企业微信") {
    return MessageCircle;
  }
  if (channel === "钉钉") {
    return Send;
  }
  return CheckCircle2;
};
