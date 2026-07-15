import { describe, expect, it } from "vitest";
import {
  isAuthResponse,
  isGithubIntegrationConfig,
  isReviewPolicyConfig,
  isReviewTaskSummary,
  validateApiResponse
} from "./responseValidation";

describe("API response validation", () => {
  it("accepts the stable fields of critical response contracts", () => {
    expect(isAuthResponse({
      accessToken: "token",
      tokenType: "Bearer",
      accessTokenExpiresInSeconds: 900,
      refreshTokenExpiresInSeconds: 604800,
      user: { id: 1, username: "admin", email: "admin@example.com", role: "ADMIN" }
    })).toBe(true);
    expect(isReviewTaskSummary({
      id: 7,
      status: "completed",
      title: "Review",
      repository: "agent",
      riskLevel: "high",
      findings: [],
      missingTests: [],
      changedFiles: [],
      timeline: []
    })).toBe(true);
    expect(isGithubIntegrationConfig({
      provider: "github",
      status: "configured",
      baseUrl: "https://api.github.com"
    })).toBe(true);
    expect(isReviewPolicyConfig(reviewPolicy())).toBe(true);
  });

  it("rejects malformed critical data with a stable contract error", () => {
    expect(isAuthResponse({ accessToken: "token" })).toBe(false);
    expect(isReviewTaskSummary({ id: 7, status: "completed" })).toBe(false);
    expect(isGithubIntegrationConfig({ provider: "github" })).toBe(false);
    expect(isReviewPolicyConfig({ ...reviewPolicy(), maxTokens: "4096" })).toBe(false);

    expect(() => validateApiResponse("login", {}, isAuthResponse, 200)).toThrowError(
      expect.objectContaining({
        code: "INVALID_API_RESPONSE",
        status: 200
      })
    );
  });
});

const reviewPolicy = () => ({
  llmEnabled: true,
  llmProvider: "openai",
  modelName: "gpt-4.1",
  timeoutSeconds: 120,
  temperature: 0.2,
  maxTokens: 4096,
  fallbackToRules: true,
  workerConcurrency: 2,
  chunkFileThreshold: 20,
  chunkLineThreshold: 800,
  chunkMaxFiles: 10,
  chunkMaxLines: 1200,
  inputTokenPricePerMillion: 2,
  outputTokenPricePerMillion: 8
});
