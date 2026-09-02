import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  fetchLlmEvaluationReports,
  fetchLlmModelReleaseCenter,
  promoteLlmModelRelease,
  registerLlmModelShadowRelease,
  rollbackLlmModelRelease
} from "@/api/config";
import type { LlmEvaluationReport, LlmModelReleaseCenter } from "@/types";
import { buildLlmModelReleaseRequest, useLlmModelReleaseCenter } from "./useLlmModelReleaseCenter";

vi.mock("@/api/config", () => ({
  fetchLlmEvaluationReports: vi.fn(),
  fetchLlmModelReleaseCenter: vi.fn(),
  promoteLlmModelRelease: vi.fn(),
  registerLlmModelShadowRelease: vi.fn(),
  rollbackLlmModelRelease: vi.fn()
}));

describe("useLlmModelReleaseCenter", () => {
  const loadCenter = vi.mocked(fetchLlmModelReleaseCenter);
  const loadReports = vi.mocked(fetchLlmEvaluationReports);
  const registerShadow = vi.mocked(registerLlmModelShadowRelease);
  const promote = vi.mocked(promoteLlmModelRelease);
  const rollback = vi.mocked(rollbackLlmModelRelease);

  beforeEach(() => {
    vi.clearAllMocks();
    loadCenter.mockResolvedValue(center());
    loadReports.mockResolvedValue([report()]);
    registerShadow.mockResolvedValue({} as never);
    promote.mockResolvedValue({} as never);
    rollback.mockResolvedValue({} as never);
  });

  it("builds release requests entirely from report evidence", () => {
    const request = buildLlmModelReleaseRequest(report(), " next ", 10);

    expect(request).toMatchObject({
      releaseKey: "next",
      provider: "openai",
      modelName: "gpt-next",
      trafficPercent: 10,
      evaluationReportId: 77,
      precisionRate: 0.95,
      p95LatencyMs: 1200
    });
  });

  it("loads reports, registers shadow, promotes and refreshes server state", async () => {
    const state = useLlmModelReleaseCenter();
    await state.load();
    state.releaseKey.value = "release-next";
    await state.registerShadow();
    await state.promote();

    expect(loadCenter).toHaveBeenCalledWith(30);
    expect(registerShadow).toHaveBeenCalledWith(expect.objectContaining({ releaseKey: "release-next", trafficPercent: 0 }));
    expect(promote).toHaveBeenCalledWith(expect.objectContaining({ releaseKey: "release-next", trafficPercent: 10 }));
    expect(state.errorMessage.value).toBe("");
  });

  it("rejects incomplete actions and keeps rollback reason explicit", async () => {
    const state = useLlmModelReleaseCenter();
    await state.registerShadow();
    expect(state.errorMessage.value).toContain("填写发布键");

    await state.rollback(11, "  ");
    expect(rollback).not.toHaveBeenCalled();
    expect(state.errorMessage.value).toContain("回滚必须填写原因");

    state.releaseKey.value = "release-next";
    await state.load();
    await state.rollback(11, "  incident  ");
    expect(rollback).toHaveBeenCalledWith(11, { reason: "incident" });
  });
});

const report = (): LlmEvaluationReport => ({
  id: 77,
  reportKey: "report-key",
  status: "COMPLETED",
  datasetId: "dataset-1",
  datasetVersion: "v1",
  datasetKind: "REAL_PR",
  sourceRepositoryCount: 2,
  sampleCount: 50,
  fixedRegressionSamples: 25,
  rollingObservationSamples: 25,
  authorized: true,
  anonymized: true,
  humanReviewed: true,
  sampleFingerprint: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  provider: "openai",
  model: "gpt-next",
  promptVersion: "prompt-v1",
  contextVersion: "context-v1",
  schemaVersion: "schema-v1",
  chunkPolicyVersion: "chunk-v1",
  temperature: 0.1,
  ruleVersion: "rule-v1",
  codeRevision: "code-v1",
  expectedFindings: 10,
  predictedFindings: 10,
  truePositives: 10,
  falsePositives: 0,
  falseNegatives: 0,
  precision: 0.95,
  recall: 0.85,
  precisionWilsonLowerBound: 0.9,
  anchorRate: 0.98,
  duplicateRate: 0.01,
  parseFailureRate: 0.01,
  severityConfusion: {},
  totalLatencyMs: 60000,
  totalTokens: 1000,
  totalCost: 0.01,
  blockers: [],
  eligible: true,
  metrics: {
    labeledComments: 0,
    usefulComments: 0,
    falsePositiveComments: 0,
    publishAttempts: 0,
    publishedComments: 0,
    fixedComments: 0,
    ignoredComments: 0,
    usefulCommentRate: 0,
    falsePositiveCommentRate: 0,
    publishSuccessRate: 0,
    fixRate: 0,
    ignoredRate: 0,
    p50LatencyMs: 800,
    p95LatencyMs: 1200,
    averageLatencyMs: 1000,
    averageTokensPerSample: 20,
    averageCostPerSample: 0.01,
    ruleFindings: 2,
    llmFindings: 8,
    verifiedFindings: 8,
    ruleContributionRate: 0.2,
    llmContributionRate: 0.8,
    verifiedContributionRate: 0.8
  },
  createdBy: "tester"
});

const center = (): LlmModelReleaseCenter => ({
  configuredProvider: "openai",
  configuredModel: "gpt-configured",
  releases: [],
  modelComparison: [],
  monthlyBudget: {
    month: "2026-09",
    tokenBudget: 1000,
    tokenUsed: 0,
    tokenRemaining: 1000,
    costBudget: 10,
    costUsed: 0,
    costRemaining: 10,
    exhausted: false
  },
  recommendedAction: "RUN_SHADOW_EVALUATION_FOR_NEXT_VERSION"
});
