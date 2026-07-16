import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  GithubIntegrationConfig,
  ReviewPolicyConfig,
  ServiceIntegrationConfig
} from "@/types";

const { showError, showSuccess, showWarning } = vi.hoisted(() => ({
  showError: vi.fn(),
  showSuccess: vi.fn(),
  showWarning: vi.fn()
}));

vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: { error: showError, success: showSuccess, warning: showWarning }
}));

import { useIntegrationConfigPersistence } from "./useIntegrationConfigPersistence";

describe("useIntegrationConfigPersistence", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("reports each partial save result and reloads server state", async () => {
    const github = githubConfig();
    const mysql = serviceConfig("mysql");
    const rabbitMq = serviceConfig("rabbitmq");
    const reviewPolicy = policyConfig();
    const applyGithubConfig = vi.fn();
    const applyReviewPolicyConfig = vi.fn();
    const applyServiceConfig = vi.fn();
    const requests = {
      fetchGithubIntegrationConfig: vi.fn().mockResolvedValue(github),
      fetchMysqlIntegrationConfig: vi.fn().mockResolvedValue(mysql),
      fetchRabbitMqIntegrationConfig: vi.fn().mockResolvedValue(rabbitMq),
      fetchReviewPolicyConfig: vi.fn().mockResolvedValue(reviewPolicy),
      updateGithubIntegrationConfig: vi.fn().mockResolvedValue(github),
      updateMysqlIntegrationConfig: vi.fn().mockRejectedValue(new Error("database unavailable")),
      updateRabbitMqIntegrationConfig: vi.fn().mockResolvedValue(rabbitMq),
      updateReviewPolicyConfig: vi.fn().mockResolvedValue(reviewPolicy)
    };
    const persistence = useIntegrationConfigPersistence({
      applyGithubConfig,
      applyReviewPolicyConfig,
      applyServiceConfig,
      canManage: { value: true },
      payloads: payloads(reviewPolicy),
      requests
    });

    await persistence.saveConfig();

    expect(requests.fetchGithubIntegrationConfig).toHaveBeenCalledOnce();
    expect(requests.fetchMysqlIntegrationConfig).toHaveBeenCalledOnce();
    expect(requests.fetchRabbitMqIntegrationConfig).toHaveBeenCalledOnce();
    expect(requests.fetchReviewPolicyConfig).toHaveBeenCalledOnce();
    expect(persistence.githubConfig.value).toEqual(github);
    expect(persistence.mysqlConfig.value).toEqual(mysql);
    expect(persistence.rabbitMqConfig.value).toEqual(rabbitMq);
    expect(persistence.reviewPolicyConfig.value).toEqual(reviewPolicy);
    expect(showError).toHaveBeenCalledWith(expect.stringContaining("成功：GitHub、RabbitMQ、审查策略"));
    expect(showError).toHaveBeenCalledWith(expect.stringContaining("失败：MySQL（database unavailable）"));
    expect(showError).toHaveBeenCalledWith(expect.stringContaining("服务端状态已重新加载"));
    expect(showSuccess).not.toHaveBeenCalled();
  });

  it("loads available configs when one fetch fails", async () => {
    const github = githubConfig();
    const mysql = serviceConfig("mysql");
    const rabbitMq = serviceConfig("rabbitmq");
    const reviewPolicy = policyConfig();
    const applyGithubConfig = vi.fn();
    const persistence = useIntegrationConfigPersistence({
      applyGithubConfig,
      applyReviewPolicyConfig: vi.fn(),
      applyServiceConfig: vi.fn(),
      canManage: { value: true },
      payloads: payloads(reviewPolicy),
      requests: {
        fetchGithubIntegrationConfig: vi.fn().mockResolvedValue(github),
        fetchMysqlIntegrationConfig: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(mysql),
        fetchRabbitMqIntegrationConfig: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(rabbitMq),
        fetchReviewPolicyConfig: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(reviewPolicy),
        updateGithubIntegrationConfig: vi.fn(),
        updateMysqlIntegrationConfig: vi.fn(),
        updateRabbitMqIntegrationConfig: vi.fn(),
        updateReviewPolicyConfig: vi.fn()
      }
    });

    await persistence.loadConfig();

    expect(applyGithubConfig).toHaveBeenCalledWith(github);
    expect(persistence.githubConfig.value).toEqual(github);
    expect(persistence.loadErrorMessage.value).toContain("MySQL、RabbitMQ、审查策略");
    expect(showWarning).not.toHaveBeenCalled();

    await persistence.loadConfig();
    expect(persistence.loadErrorMessage.value).toBe("");
  });
});

const githubConfig = (): GithubIntegrationConfig => ({
  provider: "github",
  status: "configured",
  baseUrl: "https://api.github.com"
});

const serviceConfig = (provider: string): ServiceIntegrationConfig => ({
  provider,
  status: "configured",
  baseUrl: `https://${provider}.example.com`
});

const policyConfig = (): ReviewPolicyConfig => ({
  llmEnabled: true,
  llmProvider: "openai",
  modelName: "test-model",
  timeoutSeconds: 60,
  temperature: 0,
  maxTokens: 1000,
  fallbackToRules: true,
  workerConcurrency: 1,
  chunkFileThreshold: 10,
  chunkLineThreshold: 100,
  chunkMaxFiles: 5,
  chunkMaxLines: 500,
  inputTokenPricePerMillion: 0,
  outputTokenPricePerMillion: 0
});

const payloads = (reviewPolicy: ReviewPolicyConfig) => ({
  githubPayload: () => ({ baseUrl: "https://api.github.com" }),
  mysqlPayload: () => ({ baseUrl: "mysql://localhost" }),
  rabbitMqPayload: () => ({ baseUrl: "amqp://localhost" }),
  springAiPayload: () => reviewPolicy
});
