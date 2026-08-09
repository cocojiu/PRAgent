import { describe, expect, it } from "vitest";
import {
  buildSecretReEncryptionRequest,
  canSubmitSecretReEncryption,
  secretReEncryptionExecutionConfirmMessage
} from "./secretReEncryptionRequestBuilders";

describe("secretReEncryptionRequestBuilders", () => {
  it("keeps dry-run payload free of execution confirmation text", () => {
    const payload = buildSecretReEncryptionRequest({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: " old-key ",
      targetEncryptionKey: "target-secret",
      targetKeyId: " new-key ",
      confirmText: "RE-ENCRYPT"
    }, false);

    expect(payload).toEqual({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "old-key",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key",
      execute: false,
      confirmText: undefined
    });
  });

  it("includes confirmation text only for execution payloads", () => {
    const payload = buildSecretReEncryptionRequest({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key",
      confirmText: "RE-ENCRYPT"
    }, true);

    expect(payload).toMatchObject({
      sourceKeyId: undefined,
      execute: true,
      confirmText: "RE-ENCRYPT"
    });
  });

  it("requires all cryptographic inputs before enabling submit", () => {
    expect(canSubmitSecretReEncryption({
      sourceEncryptionKey: "source-secret",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key"
    })).toBe(true);
    expect(canSubmitSecretReEncryption({
      sourceEncryptionKey: "source-secret",
      targetEncryptionKey: "",
      targetKeyId: "new-key"
    })).toBe(false);
    expect(canSubmitSecretReEncryption({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "same-key",
      targetEncryptionKey: "target-secret",
      targetKeyId: "same-key"
    })).toBe(false);
  });

  it("renders execution confirmation with source and target key ids", () => {
    const message = secretReEncryptionExecutionConfirmMessage({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "old-key",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key"
    });

    expect(message).toContain("old-key 重加密到 new-key");
    expect(message).toContain("只能在维护窗口执行");
    expect(message).toContain("立即切换活动密钥");
  });
});
