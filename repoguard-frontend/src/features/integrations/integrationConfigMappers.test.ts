import { describe, expect, it } from "vitest";
import {
  buildGithubIntegrationPatch,
  buildReviewPolicyIntegrationPatch,
  buildServiceIntegrationPatch
} from "./integrationConfigMappers";

describe("integration config mappers", () => {
  it("shows GitHub key mismatch as an actionable secret diagnostic", () => {
    const patch = buildGithubIntegrationPatch({
      provider: "GITHUB",
      status: "configured",
      baseUrl: "https://api.github.com",
      secretStatus: "key_mismatch",
      updatedAt: "2026-07-07 01:10:00"
    });

    expect(patch.status).toBe("failed");
    expect(patch.statusText).toBe("密钥不匹配");
    expect(patch.message).toContain("key id");
    expect(patch.diagnostics).toContainEqual({
      label: "密钥状态",
      value: "密钥不匹配",
      status: "danger"
    });
  });

  it("keeps service config visible when saved secret cannot be decrypted", () => {
    const patch = buildServiceIntegrationPatch("mysql", {
      provider: "MYSQL",
      status: "configured",
      baseUrl: "jdbc:mysql://localhost:3306/repoguard",
      username: "root",
      resource: "repoguard",
      secretStatus: "decrypt_failed"
    });

    expect(patch.status).toBe("failed");
    expect(patch.statusText).toBe("密文异常");
    expect(patch.message).toContain("不可解密");
    expect(patch.fields.find((field) => field.label === "JDBC URL")?.value).toBe(
      "jdbc:mysql://localhost:3306/repoguard"
    );
    expect(patch.diagnostics).toContainEqual({
      label: "密钥状态",
      value: "密文不可解密",
      status: "danger"
    });
  });

  it("shows LLM secret configured and missing states consistently", () => {
    const configured = buildReviewPolicyIntegrationPatch({
      llmEnabled: true,
      llmProvider: "dashscope",
      modelName: "qwen-plus",
      apiKey: "****1234",
      secretStatus: "configured",
      timeoutSeconds: 60,
      temperature: 0.2,
      maxTokens: 4096,
      fallbackToRules: true,
      workerConcurrency: 1,
      chunkFileThreshold: 6,
      chunkLineThreshold: 700,
      chunkMaxFiles: 4,
      chunkMaxLines: 450,
      inputTokenPricePerMillion: 0,
      outputTokenPricePerMillion: 0
    });
    const missing = buildReviewPolicyIntegrationPatch({
      ...configuredConfig(),
      apiKey: undefined,
      secretStatus: "missing"
    });

    expect(configured.diagnostics).toContainEqual({
      label: "密钥状态",
      value: "已配置",
      status: "success"
    });
    expect(missing.status).toBe("missing_secret");
    expect(missing.diagnostics).toContainEqual({
      label: "密钥状态",
      value: "未配置",
      status: "warning"
    });
  });
});

const configuredConfig = () => ({
  llmEnabled: true,
  llmProvider: "dashscope",
  modelName: "qwen-plus",
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  apiKey: "****1234",
  secretStatus: "configured" as const,
  timeoutSeconds: 60,
  temperature: 0.2,
  maxTokens: 4096,
  fallbackToRules: true,
  workerConcurrency: 1,
  chunkFileThreshold: 6,
  chunkLineThreshold: 700,
  chunkMaxFiles: 4,
  chunkMaxLines: 450,
  inputTokenPricePerMillion: 0,
  outputTokenPricePerMillion: 0
});
