import { describe, expect, it } from "vitest";
import {
  secretReEncryptionSummaryText,
  toSecretReEncryptionDisplayItems
} from "./secretReEncryptionDisplayMappers";
import type { SecretReEncryptionResponse } from "@/types";

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
        message: "Encrypted secret key id does not match source encryption key id"
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
  });

  it("summarizes dry-run and execution results with stable counts", () => {
    const response: SecretReEncryptionResponse = {
      executed: false,
      scannedCount: 6,
      reEncryptedCount: 2,
      skippedCount: 3,
      failedCount: 1,
      items: []
    };

    expect(secretReEncryptionSummaryText(response)).toBe("预检完成：扫描 6 项，需处理 2 项，跳过 3 项，失败 1 项");
    expect(secretReEncryptionSummaryText({ ...response, executed: true }))
      .toBe("已执行：扫描 6 项，需处理 2 项，跳过 3 项，失败 1 项");
  });
});
