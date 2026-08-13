import { describe, expect, it } from "vitest";
import {
  secretReEncryptionSummaryText,
  toSecretReEncryptionDisplayItems
} from "./secretReEncryptionDisplayMappers";
import type { SecretReEncryptionJob } from "@/types";

describe("secretReEncryptionDisplayMappers", () => {
  it("maps field-level preflight statuses into admin repair hints", () => {
    const items = toSecretReEncryptionDisplayItems([
      {
        tableName: "notification_channel_binding",
        recordId: 8,
        fieldName: "webhook_url_value",
        provider: "dingtalk",
        sourceFormat: "enc:v2",
        sourceKeyId: "old-key",
        targetKeyId: "new-key",
        status: "WOULD_RE_ENCRYPT",
        message: "Secret can be re-encrypted"
      },
      {
        tableName: "notification_channel_binding",
        recordId: 8,
        fieldName: "secret_value",
        provider: "dingtalk",
        sourceFormat: "enc:v2",
        sourceKeyId: "unexpected-key",
        targetKeyId: "new-key",
        status: "KEY_MISMATCH",
        failureReason: "source_key_mismatch",
        message: "Encrypted secret key id does not match source encryption key id"
      },
      {
        tableName: "integration_config",
        recordId: 9,
        fieldName: "token_value",
        provider: "GITHUB",
        sourceFormat: "enc:v2",
        sourceKeyId: "old-key",
        targetKeyId: "new-key",
        status: "DECRYPT_FAILED",
        failureReason: "source_decrypt_failed",
        message: "low-level crypto detail"
      },
      {
        tableName: "integration_config",
        recordId: 10,
        fieldName: "token_value",
        provider: "GITHUB",
        sourceFormat: "enc:v3",
        sourceKeyId: "new-key",
        targetKeyId: "new-key",
        status: "DECRYPT_FAILED",
        failureReason: "target_decrypt_failed",
        message: "low-level target crypto detail"
      }
    ]);

    expect(items[0]).toMatchObject({
      resourceText: "通知渠道 #8 / dingtalk",
      fieldText: "Webhook URL",
      statusText: "可重加密",
      statusTone: "warning",
      keyText: "old-key -> new-key"
    });
    expect(items[0].hint).toContain("可使用目标密钥重加密");
    expect(items[1]).toMatchObject({
      fieldText: "签名 Secret",
      statusText: "源密钥不匹配",
      statusTone: "danger",
      keyText: "unexpected-key -> new-key"
    });
    expect(items[1].hint).toContain("源 key id 不一致");
    expect(items[2]).toMatchObject({
      fieldText: "连接密钥",
      statusText: "解密失败",
      statusTone: "danger"
    });
    expect(items[2].hint).toBe("源密钥无法解密该字段，请重新填写密钥或修复密文。");
    expect(items[3]).toMatchObject({
      fieldText: "连接密钥",
      statusText: "解密失败",
      statusTone: "danger"
    });
    expect(items[3].hint).toBe("字段标记为目标 key id，但目标密钥无法解密，请修复损坏密文后再切换密钥。");
  });

  it("summarizes background job status with stable counts", () => {
    const response: SecretReEncryptionJob = {
      id: 7,
      executed: false,
      status: "COMPLETED_WITH_ERRORS",
      sourceKeyId: "old-key",
      targetKeyId: "new-key",
      currentTable: "done",
      checkpointId: 0,
      batchSize: 100,
      scannedCount: 6,
      reEncryptedCount: 2,
      skippedCount: 3,
      failedCount: 1,
      retryCount: 0
    };

    expect(secretReEncryptionSummaryText(response))
      .toBe("预检任务 完成但存在失败项：扫描 6 项，需处理 2 项，跳过 3 项，失败 1 项");
    expect(secretReEncryptionSummaryText({ ...response, executed: true, status: "RUNNING" }))
      .toBe("重加密任务 执行中：扫描 6 项，需处理 2 项，跳过 3 项，失败 1 项");
  });
});
