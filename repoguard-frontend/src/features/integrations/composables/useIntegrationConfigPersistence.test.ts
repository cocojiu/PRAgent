import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope } from "vue";
import { buildIntegrationConfigApplyActions } from "../integrationConfigApplyActions";
import { buildGithubPayload, buildMysqlPayload, buildRabbitMqPayload, buildSpringAiPayload } from "../integrationPayloadBuilders";
import { useIntegrationFormState } from "./useIntegrationFormState";
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

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
let scope: ReturnType<typeof effectScope> | undefined;
const setupForm = () => {
  scope = effectScope();
  const form = useIntegrationFormState();
  const requests = requestActions({ github: githubConfig(), mysql: serviceConfig("mysql"),
    rabbitMq: serviceConfig("rabbitmq"), reviewPolicy: policyConfig() });
  const actions = buildIntegrationConfigApplyActions(form);
  const persistence = scope.run(() => useIntegrationConfigPersistence({
    ...actions, captureForm: form.captureForm, captureSavedForm: form.captureSavedForm,
    canManage: { value: true }, requests,
    payloads: { githubPayload: () => buildGithubPayload(form.formState), mysqlPayload: () => buildMysqlPayload(form.formState),
      rabbitMqPayload: () => buildRabbitMqPayload(form.formState), springAiPayload: () => buildSpringAiPayload(form.formState) }
  }))!;
  return { ...form, ...persistence, requests };
};

describe("integration edits and request lifetime", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => scope?.stop());

  it("preserves edits made during saving while accepting unchanged server fields", async () => {
    const state = setupForm(); await state.loadConfig();
    state.formState.mysql!["Username"] = "submitted-user";
    const saved = deferred<ServiceIntegrationConfig>(); state.requests.updateMysqlIntegrationConfig.mockReturnValueOnce(saved.promise);
    const pending = state.saveConfig("mysql");
    expect(state.requests.updateMysqlIntegrationConfig.mock.calls[0]![0].username).toBe("submitted-user");
    state.formState.mysql!["Username"] = "next-draft";
    saved.resolve({ ...serviceConfig("mysql"), username: "submitted-user", resource: "saved-database" }); await pending;
    expect(state.formState.mysql!["Username"]).toBe("next-draft");
    expect(state.formState.mysql!["Database"]).toBe("saved-database");
    expect(state.unsavedChanges.value.mysql).toBe(true);
    state.requests.updateMysqlIntegrationConfig.mockResolvedValueOnce({ ...serviceConfig("mysql"), username: "next-draft", resource: "saved-database" });
    await state.saveConfig("mysql"); expect(state.unsavedChanges.value.mysql).toBe(false);
  });

  it("preserves existing unsaved edits when retrying config reads", async () => {
    const state = setupForm(); await state.loadConfig();
    state.formState.github!["Default Owner"] = "draft-owner";
    state.requests.fetchGithubIntegrationConfig.mockResolvedValueOnce({ ...githubConfig(), defaultOwner: "remote-owner", defaultRepo: "remote-repo" });
    await state.loadConfig();
    expect(state.formState.github!["Default Owner"]).toBe("draft-owner");
    expect(state.formState.github!["Default Repo"]).toBe("remote-repo");
    expect(state.unsavedChanges.value.github).toBe(true);
  });

  it("does not let a read started before saving overwrite the saved configuration", async () => {
    const state = setupForm(); await state.loadConfig();
    const old = deferred<ServiceIntegrationConfig>(); state.requests.fetchMysqlIntegrationConfig.mockReturnValueOnce(old.promise);
    const reading = state.loadConfig(); state.formState.mysql!["Username"] = "saved-user";
    state.requests.updateMysqlIntegrationConfig.mockResolvedValueOnce({ ...serviceConfig("mysql"), username: "saved-user" });
    await state.saveConfig("mysql");
    old.resolve({ ...serviceConfig("mysql"), username: "old-user" }); await reading;
    expect(state.mysqlConfig.value?.username).toBe("saved-user"); expect(state.formState.mysql!["Username"]).toBe("saved-user");
    expect(state.unsavedChanges.value.mysql).toBe(false); expect(state.githubConfig.value).toEqual(githubConfig());
  });

  it("ignores an overlapping read for a pending save without discarding other sections", async () => {
    const state = setupForm(); await state.loadConfig();
    const saved = deferred<ServiceIntegrationConfig>(); state.requests.updateMysqlIntegrationConfig.mockReturnValueOnce(saved.promise);
    state.formState.mysql!["Username"] = "pending-user"; const saving = state.saveConfig("mysql");
    state.requests.fetchMysqlIntegrationConfig.mockResolvedValueOnce({ ...serviceConfig("mysql"), username: "old-user" });
    state.requests.fetchGithubIntegrationConfig.mockResolvedValueOnce({ ...githubConfig(), defaultOwner: "new-owner" });
    await state.loadConfig();
    expect(state.formState.mysql!["Username"]).toBe("pending-user");
    expect(state.githubConfig.value?.defaultOwner).toBe("new-owner");
    saved.resolve({ ...serviceConfig("mysql"), username: "pending-user" }); await saving;
    expect(state.unsavedChanges.value.mysql).toBe(false);
  });

  it("aborts an obsolete read and ignores its late response and error", async () => {
    const state = setupForm(); const old = deferred<GithubIntegrationConfig>();
    state.requests.fetchGithubIntegrationConfig.mockReturnValueOnce(old.promise);
    const pending = state.loadConfig();
    const signals = [state.requests.fetchGithubIntegrationConfig, state.requests.fetchMysqlIntegrationConfig,
      state.requests.fetchRabbitMqIntegrationConfig, state.requests.fetchReviewPolicyConfig]
      .map(request => (request.mock.calls[0]?.[0] as { signal: AbortSignal }).signal);
    state.requests.fetchGithubIntegrationConfig.mockResolvedValueOnce({ ...githubConfig(), defaultOwner: "new-owner" });
    await state.loadConfig(); old.reject(new Error("old failure")); await pending;
    expect(signals.every(signal => signal.aborted)).toBe(true);
    expect(state.githubConfig.value?.defaultOwner).toBe("new-owner"); expect(state.loadErrorMessage.value).toBe("");
  });

  it("keeps the latest read loading after an old request finishes", async () => {
    const state = setupForm(); const old = deferred<GithubIntegrationConfig>(); const newer = deferred<GithubIntegrationConfig>();
    state.requests.fetchGithubIntegrationConfig.mockReturnValueOnce(old.promise).mockReturnValueOnce(newer.promise);
    const pending = state.loadConfig(); const current = state.loadConfig();
    old.resolve(githubConfig()); await pending; expect(state.loading.value).toBe(true);
    newer.resolve(githubConfig()); await current; expect(state.loading.value).toBe(false);
  });

  it("retains drafts after a save fails", async () => {
    const state = setupForm(); await state.loadConfig(); state.formState.mysql!["Username"] = "unsaved-user";
    state.requests.updateMysqlIntegrationConfig.mockRejectedValueOnce(new Error("save offline"));
    await state.saveConfig("mysql");
    expect(state.formState.mysql!["Username"]).toBe("unsaved-user"); expect(state.unsavedChanges.value.mysql).toBe(true);
    expect(showError).toHaveBeenCalledWith("MySQL 配置保存失败：save offline");
  });

  it("cancels all four reads and ignores a write result after leaving the scope", async () => {
    const state = setupForm(); const old = deferred<GithubIntegrationConfig>(); const saved = deferred<ServiceIntegrationConfig>();
    state.requests.fetchGithubIntegrationConfig.mockReturnValueOnce(old.promise);
    state.requests.updateMysqlIntegrationConfig.mockReturnValueOnce(saved.promise);
    const reading = state.loadConfig(); const saving = state.saveConfig("mysql");
    const signal = (state.requests.fetchGithubIntegrationConfig.mock.calls[0]?.[0] as { signal: AbortSignal }).signal;
    scope!.stop(); old.resolve(githubConfig()); saved.resolve(serviceConfig("mysql")); await Promise.all([reading, saving]);
    expect(signal.aborted).toBe(true); expect(state.githubConfig.value).toBeUndefined(); expect(state.mysqlConfig.value).toBeUndefined();
    expect(showSuccess).not.toHaveBeenCalled(); expect(state.loading.value).toBe(false);
    await state.loadConfig(); await state.saveConfig("mysql"); expect(state.requests.fetchGithubIntegrationConfig).toHaveBeenCalledOnce();
    expect(state.requests.updateMysqlIntegrationConfig).toHaveBeenCalledOnce();
  });
});
