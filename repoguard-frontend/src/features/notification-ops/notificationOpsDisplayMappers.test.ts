import { describe, expect, it } from "vitest";
import type { NotificationBinding } from "@/types";
import {
  canRetryNotificationEvent,
  isRetryPendingNotificationEvent,
  notificationBindingSecretDisplay,
  notificationStatusClass,
  notificationStatusText
} from "./notificationOpsDisplayMappers";

const binding = (patch: Partial<NotificationBinding>): NotificationBinding => ({
  id: 1,
  name: "DingTalk",
  provider: "DINGTALK",
  organization: "octo",
  repository: "repo",
  enabled: true,
  notifyReviewCompleted: true,
  notifyReviewFailed: true,
  notifyHumanReviewRequired: true,
  notifyGithubComment: true,
  status: "CONFIGURED",
  ...patch
});

describe("notification ops display mappers", () => {
  it("shows an in-flight outbox publish without offering a duplicate retry", () => {
    expect(notificationStatusClass("PUBLISHING")).toBe("processing");
    expect(notificationStatusText("PUBLISHING")).toBe("发布中");
    expect(canRetryNotificationEvent("PUBLISHING")).toBe(false);
    expect(isRetryPendingNotificationEvent("PUBLISHING")).toBe(false);
  });

  it("shows a healthy webhook without treating optional signing secret as an issue", () => {
    const display = notificationBindingSecretDisplay(binding({
      webhookUrlStatus: "configured",
      secretStatus: "missing"
    }));

    expect(display).toMatchObject({
      text: "Webhook 已配置",
      type: "success"
    });
    expect(display.detail).toContain("签名 Secret 未配置，可按渠道要求补充");
  });

  it("surfaces webhook key mismatch before optional secret state", () => {
    const display = notificationBindingSecretDisplay(binding({
      webhookUrlStatus: "key_mismatch",
      secretStatus: "configured"
    }));

    expect(display).toMatchObject({
      text: "Webhook 密钥不匹配",
      type: "danger"
    });
    expect(display.detail).toContain("Webhook 的 key id 与当前加密密钥不匹配");
  });

  it("surfaces broken signing secret when webhook is healthy", () => {
    const display = notificationBindingSecretDisplay(binding({
      webhookUrlStatus: "configured",
      secretStatus: "decrypt_failed"
    }));

    expect(display).toMatchObject({
      text: "签名 Secret 密文异常",
      type: "danger"
    });
  });

  it("warns when the required webhook secret is missing", () => {
    const display = notificationBindingSecretDisplay(binding({
      webhookUrlStatus: "missing",
      secretStatus: "missing"
    }));

    expect(display).toMatchObject({
      text: "Webhook 未配置",
      type: "warning"
    });
  });
});
