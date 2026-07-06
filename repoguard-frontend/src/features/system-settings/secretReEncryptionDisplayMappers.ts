import type { SecretReEncryptionItem, SecretReEncryptionResponse } from "@/types";

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

export const secretReEncryptionSummaryText = (response: SecretReEncryptionResponse) => {
  const actionText = response.executed ? "已执行" : "预检完成";
  return `${actionText}：扫描 ${response.scannedCount} 项，需处理 ${response.reEncryptedCount} 项，跳过 ${response.skippedCount} 项，失败 ${response.failedCount} 项`;
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
      return item.message ? `解密失败：${item.message}` : "源密钥无法解密该字段，请重新填写密钥或修复密文。";
    case "FAILED":
      return item.message ? `处理失败：${item.message}` : "字段处理失败，请查看后端日志。";
    default:
      return item.message || "该字段返回了未知状态，请结合后端日志确认。";
  }
};
