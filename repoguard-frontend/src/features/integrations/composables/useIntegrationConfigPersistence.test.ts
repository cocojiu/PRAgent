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

  it("saves only the selected integration and applies its returned state", async () => {
    const github = githubConfig();
    const mysql = serviceConfig("mysql");
    const rabbitMq = serviceConfig("rabbitmq");
    const reviewPolicy = policyConfig();
    const applyServiceConfig = vi.fn();
    const requests = requestActions({ github, mysql, rabbitMq, reviewPolicy });
    const persistence = useIntegrationConfigPersistence({
      applyGithubConfig: vi.fn(),
      applyReviewPolicyConfig: vi.fn(),
      applyServiceConfig,
      canManage: { value: true },
      payloads: payloads(reviewPolicy),
      requests
    });

    await persistence.saveConfig("mysql");

    expect(requests.updateMysqlIntegrationConfig).toHaveBeenCalledOnce();
    expect(requests.updateGithubIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.updateRabbitMqIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.updateReviewPolicyConfig).not.toHaveBeenCalled();
    expect(requests.fetchGithubIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.fetchMysqlIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.fetchRabbitMqIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.fetchReviewPolicyConfig).not.toHaveBeenCalled();
    expect(persistence.mysqlConfig.value).toEqual(mysql);
    expect(applyServiceConfig).toHaveBeenCalledWith("mysql", mysql);
    expect(showSuccess).toHaveBeenCalledWith("MySQL 配置保存成功");
    expect(showError).not.toHaveBeenCalled();
  });

  it("reports a selected integration failure without writing or reloading other sections", async () => {
    const github = githubConfig();
    const mysql = serviceConfig("mysql");
    const rabbitMq = serviceConfig("rabbitmq");
    const reviewPolicy = policyConfig();
    const requests = requestActions({ github, mysql, rabbitMq, reviewPolicy });
    requests.updateMysqlIntegrationConfig.mockRejectedValue(new Error("database unavailable"));
    const applyServiceConfig = vi.fn();
    const persistence = useIntegrationConfigPersistence({
      applyGithubConfig: vi.fn(),
      applyReviewPolicyConfig: vi.fn(),
      applyServiceConfig,
      canManage: { value: true },
      payloads: payloads(reviewPolicy),
      requests
    });

    await persistence.saveConfig("mysql");

    expect(showError).toHaveBeenCalledWith("MySQL 配置保存失败：database unavailable");
    expect(showSuccess).not.toHaveBeenCalled();
    expect(applyServiceConfig).not.toHaveBeenCalled();
    expect(requests.updateGithubIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.updateRabbitMqIntegrationConfig).not.toHaveBeenCalled();
    expect(requests.updateReviewPolicyConfig).not.toHaveBeenCalled();
    expect(requests.fetchMysqlIntegrationConfig).not.toHaveBeenCalled();
  });

  it("loads available configs when one fetch fails", async () => {
    const github = githubConfig();
    const mysql = serviceConfig("mysql");
    const rabbitMq = serviceConfig("rabbitmq");
    const reviewPolicy = policyConfig();
    const applyGithubConfig = vi.fn();
    const requests = requestActions({ github, mysql, rabbitMq, reviewPolicy });
    requests.fetchMysqlIntegrationConfig
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(mysql);
    requests.fetchRabbitMqIntegrationConfig
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(rabbitMq);
    requests.fetchReviewPolicyConfig
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(reviewPolicy);
    const persistence = useIntegrationConfigPersistence({
      applyGithubConfig,
      applyReviewPolicyConfig: vi.fn(),
      applyServiceConfig: vi.fn(),
      canManage: { value: true },
      payloads: payloads(reviewPolicy),
      requests
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

const requestActions = ({
  github,
  mysql,
  rabbitMq,
  reviewPolicy
}: {
  github: GithubIntegrationConfig;
  mysql: ServiceIntegrationConfig;
  rabbitMq: ServiceIntegrationConfig;
  reviewPolicy: ReviewPolicyConfig;
}) => ({
  fetchGithubIntegrationConfig: vi.fn().mockResolvedValue(github),
  fetchMysqlIntegrationConfig: vi.fn().mockResolvedValue(mysql),
  fetchRabbitMqIntegrationConfig: vi.fn().mockResolvedValue(rabbitMq),
  fetchReviewPolicyConfig: vi.fn().mockResolvedValue(reviewPolicy),
  updateGithubIntegrationConfig: vi.fn().mockResolvedValue(github),
  updateMysqlIntegrationConfig: vi.fn().mockResolvedValue(mysql),
  updateRabbitMqIntegrationConfig: vi.fn().mockResolvedValue(rabbitMq),
  updateReviewPolicyConfig: vi.fn().mockResolvedValue(reviewPolicy)
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
