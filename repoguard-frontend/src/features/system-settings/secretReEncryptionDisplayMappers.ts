import type {
  SecretReEncryptionItem,
  SecretReEncryptionJob,
  SecretReEncryptionJobStatus
} from "@/types";

export type SecretReEncryptionTone = "success" | "warning" | "danger" | "info";

export interface SecretReEncryptionDisplayItem {
  resourceText: string;
  fieldText: string;
  statusText: string;
  statusTone: SecretReEncryptionTone;
  keyText: string;
  hint: string;
}

const RESOURCE_LABELS: Record<string, string> = {
  integration_config: "集成配置",
  review_policy_config: "审查策略",
  notification_channel_binding: "通知渠道"
};

const FIELD_LABELS: Record<string, string> = {
  token_value: "连接密钥",
  api_key_value: "LLM API Key",
  webhook_url_value: "Webhook URL",
  secret_value: "签名 Secret"
};

export const secretReEncryptionSummaryText = (job: SecretReEncryptionJob) => {
  const actionText = job.executed ? "重加密任务" : "预检任务";
  const failureText = job.lastFailureReason ? `；最近失败：${job.lastFailureReason}` : "";
  return `${actionText} ${jobStatusText(job.status)}：扫描 ${job.scannedCount} 项，需处理 ${job.reEncryptedCount} 项，跳过 ${job.skippedCount} 项，失败 ${job.failedCount} 项${failureText}`;
};

export const jobStatusText = (status: SecretReEncryptionJobStatus) => {
  switch (status) {
    case "PENDING":
      return "等待执行";
    case "RUNNING":
      return "执行中";
    case "RETRY_WAIT":
      return "等待重试";
    case "PAUSED":
      return "已暂停";
    case "COMPLETED":
      return "已完成";
    case "COMPLETED_WITH_ERRORS":
      return "完成但存在失败项";
    case "FAILED":
      return "任务失败";
  }
};

export const toSecretReEncryptionDisplayItems = (
  items: SecretReEncryptionItem[]
): SecretReEncryptionDisplayItem[] => items.map(item => ({
  resourceText: resourceText(item),
  fieldText: fieldText(item.fieldName),
  statusText: statusText(item.status),
  statusTone: statusTone(item.status),
  keyText: keyText(item),
  hint: statusHint(item)
}));

const resourceText = (item: SecretReEncryptionItem) => {
  const tableText = RESOURCE_LABELS[item.tableName] ?? item.tableName;
  const providerText = item.provider ? ` / ${item.provider}` : "";
  return `${tableText} #${item.recordId}${providerText}`;
};

const fieldText = (fieldName: string) => FIELD_LABELS[fieldName] ?? fieldName;

const keyText = (item: SecretReEncryptionItem) => {
  const source = item.sourceKeyId || item.sourceFormat || "未识别";
  const target = item.targetKeyId || "未指定";
  return `${source} -> ${target}`;
};

const statusText = (status: string) => {
  switch (status) {
    case "WOULD_RE_ENCRYPT":
      return "可重加密";
    case "RE_ENCRYPTED":
      return "已重加密";
    case "SKIPPED_EMPTY":
      return "未配置";
    case "SKIPPED_TARGET_KEY":
      return "已是目标密钥";
    case "KEY_MISMATCH":
      return "源密钥不匹配";
    case "DECRYPT_FAILED":
      return "解密失败";
    case "FAILED":
      return "处理失败";
    default:
      return status;
  }
};

const statusTone = (status: string): SecretReEncryptionTone => {
  switch (status) {
    case "WOULD_RE_ENCRYPT":
      return "warning";
    case "RE_ENCRYPTED":
    case "SKIPPED_TARGET_KEY":
      return "success";
    case "KEY_MISMATCH":
    case "DECRYPT_FAILED":
    case "FAILED":
      return "danger";
    default:
      return "info";
  }
};

const statusHint = (item: SecretReEncryptionItem) => {
  switch (item.status) {
    case "WOULD_RE_ENCRYPT":
      return "预检确认该字段可使用目标密钥重加密。";
    case "RE_ENCRYPTED":
      return "该字段已完成重加密，后续读取将使用目标密钥。";
    case "SKIPPED_EMPTY":
      return "该字段当前没有密文，跳过处理。";
    case "SKIPPED_TARGET_KEY":
      return "该字段已使用目标 key id 加密，无需重复处理。";
    case "KEY_MISMATCH":
      return "字段密文携带的 key id 与源 key id 不一致，请确认源密钥或单独修复该记录。";
    case "DECRYPT_FAILED":
      if (item.failureReason === "source_decrypt_failed") {
        return "源密钥无法解密该字段，请重新填写密钥或修复密文。";
      }
      if (item.failureReason === "target_decrypt_failed") {
        return "字段标记为目标 key id，但目标密钥无法解密，请修复损坏密文后再切换密钥。";
      }
      return item.message ? `解密失败：${item.message}` : "源密钥无法解密该字段，请重新填写密钥或修复密文。";
    case "FAILED":
      if (item.failureReason) {
        return `处理失败：${item.failureReason}`;
      }
      return item.message ? `处理失败：${item.message}` : "字段处理失败，请查看后端日志。";
    default:
      return item.message || "该字段返回了未知状态，请结合后端日志确认。";
  }
};
