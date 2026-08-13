import type { SecretReEncryptionRequest } from "@/types";

export const SECRET_RE_ENCRYPTION_CONFIRM_TEXT = "RE-ENCRYPT";

export interface SecretReEncryptionFormState {
  sourceEncryptionKey: string;
  sourceKeyId?: string;
  targetEncryptionKey: string;
  targetKeyId: string;
  confirmText?: string;
}

export const canSubmitSecretReEncryption = (form: SecretReEncryptionFormState) =>
  form.sourceEncryptionKey.trim().length > 0
  && form.targetEncryptionKey.trim().length > 0
  && form.targetKeyId.trim().length > 0
  && form.targetKeyId.trim() !== (form.sourceKeyId?.trim() || "local");

export const buildSecretReEncryptionRequest = (
  form: SecretReEncryptionFormState,
  execute: boolean
): SecretReEncryptionRequest => ({
  sourceEncryptionKey: form.sourceEncryptionKey,
  sourceKeyId: form.sourceKeyId?.trim() || undefined,
  targetEncryptionKey: form.targetEncryptionKey,
  targetKeyId: form.targetKeyId.trim(),
  execute,
  confirmText: execute ? form.confirmText : undefined
});

export const secretReEncryptionExecutionConfirmMessage = (form: SecretReEncryptionFormState) => {
  const sourceKeyId = form.sourceKeyId?.trim() || "local";
  const targetKeyId = form.targetKeyId.trim();
  return `确认将可解密字段从 ${sourceKeyId} 重加密到 ${targetKeyId}？该任务会分批更新密文，只能在维护窗口执行；完成后需立即切换活动密钥并重启服务。`;
};
