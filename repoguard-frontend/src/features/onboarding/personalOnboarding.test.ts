import { describe, expect, it } from "vitest";
import {
  buildPersonalOnboardingSteps,
  isGithubSetupComplete,
  isLlmSetupComplete
} from "./personalOnboarding";

const githubConfig = (overrides: Record<string, unknown> = {}) => ({
  provider: "github",
  status: "configured" as const,
  baseUrl: "https://api.github.com",
  token: "****1234",
  secretStatus: "configured" as const,
  defaultOwner: "owner",
  defaultRepo: "repository",
  ...overrides
});

const llmConfig = (overrides: Record<string, unknown> = {}) => ({
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
  outputTokenPricePerMillion: 0,
  ...overrides
});

describe("personal onboarding", () => {
  it("requires a default GitHub repository in addition to a configured token", () => {
    expect(isGithubSetupComplete(githubConfig())).toBe(true);
    expect(isGithubSetupComplete(githubConfig({ defaultRepo: "" }))).toBe(false);
    expect(isGithubSetupComplete(githubConfig({ secretStatus: "key_mismatch" }))).toBe(false);
  });

  it("allows the local mock provider without an external key but protects real providers", () => {
    expect(isLlmSetupComplete(llmConfig())).toBe(true);
    expect(isLlmSetupComplete(llmConfig({ apiKey: undefined, secretStatus: "missing" }))).toBe(false);
    expect(isLlmSetupComplete(llmConfig({ llmProvider: "mock", apiKey: undefined, secretStatus: "missing" }))).toBe(true);
  });

  it("keeps preview disabled until both checks and the repository probe pass", () => {
    const steps = buildPersonalOnboardingSteps({
      githubConfigured: true,
      llmConfigured: true,
      githubConnectionVerified: true,
      llmConnectionVerified: true,
      repositoryChecked: true,
      pullRequestCount: 0,
      previewLaunched: false
    });

    expect(steps.map(step => step.state)).toEqual(["done", "done", "done", "current"]);
    expect(steps[3].actionEnabled).toBe(false);
    expect(steps[3].description).toContain("暂无 open PR");
  });
});
