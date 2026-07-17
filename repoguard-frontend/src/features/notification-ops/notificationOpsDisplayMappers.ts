import { Bell, CheckCircle2, Mail, MessageCircle, RotateCcw, Send, XCircle } from "@lucide/vue";
import type { NotificationBinding, NotificationEvent } from "@/types";

const FAILED_EVENT_STATUSES = ["PUBLISH_FAILED", "DELIVERY_FAILED", "FAILED", "DEAD"];
const RETRYABLE_EVENT_STATUSES = ["PUBLISH_FAILED", "DELIVERY_FAILED", "FAILED", "DEAD", "PENDING"];
const RETRY_PENDING_EVENT_STATUSES = ["PENDING", "PUBLISH_FAILED", "DELIVERY_FAILED"];
type SecretTagType = "success" | "warning" | "danger" | "info";

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

export const notificationBindingSecretDisplay = (binding: NotificationBinding) => {
  const webhook = secretStatusLabel("Webhook", binding.webhookUrlStatus, true);
  const signingSecret = secretStatusLabel("签名 Secret", binding.secretStatus, false);
  const fields = [webhook, signingSecret];
  const issueFields = fields.filter((item) => item.visible);
  const issue = fields.find((item) => item.status === "danger") ?? fields.find((item) => item.status === "warning");
  if (issue) {
    return {
      text: issue.text,
      type: issue.status,
      detail: issueFields.map((item) => item.detail).join("；")
    };
  }
  return {
    text: signingSecret.configured ? "Webhook/签名已配置" : "Webhook 已配置",
    type: "success" as SecretTagType,
    detail: fields.map((item) => item.detail).join("；")
  };
};

export const channelText = (event: NotificationEvent) => {
  const providers = event.deliverySummary?.providers ?? [];
  if (!providers.length) {
    return "-";
  }
  return providers.map(providerText).join(" / ");
};

const secretStatusLabel = (
  label: string,
  status: NotificationBinding["webhookUrlStatus"],
  required: boolean
) => {
  if (status === "key_mismatch") {
    return {
      text: `${label} 密钥不匹配`,
      detail: `${label} 的 key id 与当前加密密钥不匹配`,
      status: "danger" as SecretTagType,
      configured: false,
      visible: true
    };
  }
  if (status === "decrypt_failed") {
    return {
      text: `${label} 密文异常`,
      detail: `${label} 密文不可解密`,
      status: "danger" as SecretTagType,
      configured: false,
      visible: true
    };
  }
  if (status === "configured") {
    return {
      text: `${label} 已配置`,
      detail: `${label} 已配置`,
      status: "success" as SecretTagType,
      configured: true,
      visible: true
    };
  }
  return {
    text: required ? `${label} 未配置` : `${label} 未配置`,
    detail: required ? `${label} 未配置` : `${label} 未配置，可按渠道要求补充`,
    status: required ? "warning" as SecretTagType : "info" as SecretTagType,
    configured: false,
    visible: required
  };
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

export const buildNotificationMetricItems = ({
  notificationBindings,
  notificationEvents,
  notificationEventTotal
}: {
  notificationBindings: NotificationBinding[];
  notificationEvents: NotificationEvent[];
  notificationEventTotal: number;
}) => {
  const failedCount = notificationEvents.filter((event) => isFailedNotificationEvent(event.status)).length;
  const retryPendingCount = notificationEvents.filter((event) => isRetryPendingNotificationEvent(event.status)).length;
  const enabledBindingCount = notificationBindings.filter((binding) => binding.enabled).length;
  return [
    { label: "今日通知", value: notificationEventTotal || notificationEvents.length, theme: "blue", icon: Send },
    { label: "失败", value: failedCount, theme: "red", icon: XCircle },
    { label: "待重试", value: retryPendingCount, theme: "orange", icon: RotateCcw },
    { label: "启用渠道", value: enabledBindingCount, theme: "green", icon: Bell }
  ];
};
