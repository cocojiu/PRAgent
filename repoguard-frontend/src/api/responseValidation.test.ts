import { describe, expect, it } from "vitest";
import {
  isAuthResponse,
  isCodeownersRecommendations,
  isCodeownersAcceptance,
  isReviewAssignmentOptions,
  isReviewMemberAssignment,
  isFeedbackSummary,
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

it("rejects incomplete feedback observations and accepts explicit empty evidence", () => {
  const value = { windowStart: "2026-08-18", windowEnd: "2026-09-17", examinedCount: 0, excludedOrDuplicateCount: 0,
    sampleSize: 0, truncated: false, sources: ["RULE", "LLM"].map(source => ({ source, reviewed: 0, valid: 0,
      falsePositive: 0, fixed: 0, ignored: 0, evidenceStatus: "INSUFFICIENT_DATA" })), details: [] };
  expect(isFeedbackSummary(value)).toBe(true);
  expect(isFeedbackSummary({ ...value, sources: [] })).toBe(false);
  expect(isFeedbackSummary({ ...value, details: [{ taskId: 1 }] })).toBe(false);
  expect(isFeedbackSummary({ ...value, sampleSize: "unknown" })).toBe(false);
});

describe("CODEOWNERS response validation", () => {
  const result = () => ({ status: "RECOMMENDATIONS", taskId: 9, attemptId: 12, headSha: "a".repeat(40),
    baseSha: "b".repeat(40), sourcePath: "CODEOWNERS", candidates: [{ userId: 2, username: "reviewer",
      coveredFiles: 1, findingCount: 0, riskScore: 1, paths: ["a.java"], externalIdentities: ["@owner"] }],
    basis: [{ path: "a.java", pattern: "*", line: 1, owners: ["@owner"], changedFiles: ["a.java"] }], uncoveredPaths: [], unmappedIdentities: [] });
  it("accepts recommendations and disabled empty projections", () => {
    expect(isCodeownersRecommendations(result())).toBe(true);
    expect(isCodeownersRecommendations({ ...result(), status: "DISABLED", headSha: null, baseSha: null,
      attemptId: null, sourcePath: null, candidates: [], basis: [] })).toBe(true);
  });
  it("rejects missing collections and incomplete recommendation provenance", () => {
    for (const invalid of [{ basis: null }, { uncoveredPaths: null }, { candidates: [] }, { attemptId: null },
      { baseSha: "branch-name" }, { sourcePath: "private/config.json" }]) {
      expect(isCodeownersRecommendations({ ...result(), ...invalid })).toBe(false);
    }
  });
  it("rejects malformed or over-budget candidate data", () => {
    const candidate = result().candidates[0]!;
    for (const invalid of [{ userId: -1 }, { findingCount: -1 }, { coveredFiles: 0 }, { username: "" },
      { paths: Array(201).fill("a.java") }, { externalIdentities: ["x".repeat(256)] }]) {
      expect(isCodeownersRecommendations({ ...result(), candidates: [{ ...candidate, ...invalid }] })).toBe(false);
    }
    expect(isCodeownersRecommendations({ ...result(), candidates: Array(4).fill(candidate) })).toBe(false);
    expect(isCodeownersRecommendations({ ...result(), candidates: [candidate, candidate] })).toBe(false);
  });
  it("rejects over-budget rules and path projections", () => {
    expect(isCodeownersRecommendations({ ...result(), basis: Array(201).fill(result().basis[0]) })).toBe(false);
    expect(isCodeownersRecommendations({ ...result(), basis: [{ ...result().basis[0], owners: Array(21).fill("@owner") }] })).toBe(false);
    expect(isCodeownersRecommendations({ ...result(), uncoveredPaths: ["x".repeat(513)] })).toBe(false);
    expect(isCodeownersRecommendations({ ...result(), unmappedIdentities: Array(4001).fill("@owner") })).toBe(false);
  });
});

it("validates assignment confirmation identity and version before updating the UI", () => {
  const result = { taskId: 9, attemptId: 12, headSha: "a".repeat(40), assignee: "reviewer" };
  expect(isCodeownersAcceptance(result)).toBe(true);
  for (const invalid of [{ taskId: 0 }, { attemptId: 0 }, { headSha: "branch" }, { assignee: "" }, { assignee: null }])
    expect(isCodeownersAcceptance({ ...result, ...invalid })).toBe(false);
});

it("accepts rename coverage and rejects missing change relationships", () => {
  const value = { status: "RECOMMENDATIONS", taskId: 9, attemptId: 12, headSha: "a".repeat(40), baseSha: "b".repeat(40), sourcePath: "CODEOWNERS",
    candidates: [{ userId: 2, username: "reviewer", coveredFiles: 1, findingCount: 2, riskScore: 4, paths: ["new/a", "old/a"], externalIdentities: ["@owner"] }],
    basis: [{ path: "old/a", pattern: "*", line: 1, owners: ["@owner"], changedFiles: ["new/a"] }], uncoveredPaths: [], unmappedIdentities: [] };
  expect(isCodeownersRecommendations(value)).toBe(true);
  expect(isCodeownersRecommendations({ ...value, candidates: [{ ...value.candidates[0], coveredFiles: 2, paths: ["old/a"] }] })).toBe(true);
  expect(isCodeownersRecommendations({ ...value, basis: [{ ...value.basis[0], changedFiles: [] }] })).toBe(false);
});

it("bounds member options and requires unique active identities and complete assignment version", () => {
  const data = { taskId: 9, attemptId: 12, headSha: "a".repeat(40), assignmentVersion: "b".repeat(64),
    members: [{ userId: 11, username: "reviewer" }], hasMore: false };
  expect(isReviewAssignmentOptions(data)).toBe(true);
  for (const invalid of [{ assignmentVersion: "short" }, { headSha: "short" }, { attemptId: null }, { hasMore: "false" },
    { members: [data.members[0], data.members[0]] }, { members: Array.from({ length: 21 }, (_, i) => ({ userId: i + 1, username: "r" })) },
    { members: [{ userId: 0, username: "r" }] }, { members: [{ userId: 1, username: " " }] }])
    expect(isReviewAssignmentOptions({ ...data, ...invalid })).toBe(false);
  expect(isReviewMemberAssignment({ taskId: 9, attemptId: 12, headSha: data.headSha, assignee: "reviewer" })).toBe(true);
  expect(isReviewMemberAssignment({ taskId: 9, attemptId: 12, headSha: data.headSha, assignee: "" })).toBe(false);
});
