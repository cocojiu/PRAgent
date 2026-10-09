import { effectScope } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  exportLlmModelReleaseAudits,
  fetchLlmEvaluationReports,
  fetchLlmModelReleaseAudits,
  fetchLlmModelReleaseCenter,
  fetchLlmModelReleaseRuntimeMetrics,
  promoteLlmModelRelease,
  registerLlmModelShadowRelease,
  rollbackLlmModelRelease,
  verifyLlmModelReleaseAudit,
  transitionLlmEvaluationReportLifecycle
} from "@/api/config";
import type { LlmEvaluationReport, LlmModelReleaseCenter } from "@/types";
import { buildLlmModelReleaseRequest, selectedEvaluationSampleIds, useLlmModelReleaseCenter } from "./useLlmModelReleaseCenter";

vi.mock("@/api/config", () => ({
  fetchLlmEvaluationReports: vi.fn(),
  fetchLlmModelReleaseCenter: vi.fn(),
  fetchLlmModelReleaseRuntimeMetrics: vi.fn(),
  fetchLlmModelReleaseAudits: vi.fn(),
  verifyLlmModelReleaseAudit: vi.fn(),
  exportLlmModelReleaseAudits: vi.fn(),
  promoteLlmModelRelease: vi.fn(),
  registerLlmModelShadowRelease: vi.fn(),
  rollbackLlmModelRelease: vi.fn(),
  transitionLlmEvaluationReportLifecycle: vi.fn()
}));

describe("useLlmModelReleaseCenter", () => {
  const loadCenter = vi.mocked(fetchLlmModelReleaseCenter);
  const loadReports = vi.mocked(fetchLlmEvaluationReports);
  const loadRuntimeMetrics = vi.mocked(fetchLlmModelReleaseRuntimeMetrics);
  const loadAudits = vi.mocked(fetchLlmModelReleaseAudits);
  const verifyAudit = vi.mocked(verifyLlmModelReleaseAudit);
  const exportAudits = vi.mocked(exportLlmModelReleaseAudits);
  const registerShadow = vi.mocked(registerLlmModelShadowRelease);
  const promote = vi.mocked(promoteLlmModelRelease);
  const rollback = vi.mocked(rollbackLlmModelRelease);
  const transitionLifecycle = vi.mocked(transitionLlmEvaluationReportLifecycle);

  beforeEach(() => {
    vi.clearAllMocks();
    loadCenter.mockResolvedValue(center());
    loadReports.mockResolvedValue([report()]);
    loadRuntimeMetrics.mockResolvedValue([]);
    loadAudits.mockResolvedValue({ items: [], total: 0, nextCursor: null, hasMore: false });
    verifyAudit.mockResolvedValue({
      auditId: 1,
      releaseId: 7,
      releaseKey: "release-next",
      eventHash: "hash",
      calculatedHash: "hash",
      valid: true,
      status: "VALID"
    });
    exportAudits.mockResolvedValue({
      format: "csv",
      recordCount: 0,
      contentSha256: "hash",
      content: ""
    });
    registerShadow.mockResolvedValue({} as never);
    promote.mockResolvedValue({} as never);
    rollback.mockResolvedValue({} as never);
    transitionLifecycle.mockResolvedValue({} as never);
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

    expect(loadCenter).toHaveBeenCalledWith(30, { signal: expect.any(AbortSignal) });
    expect(loadRuntimeMetrics).toHaveBeenCalledWith({ days: 30, limit: 168 }, { signal: expect.any(AbortSignal) });
    expect(loadAudits).toHaveBeenCalledWith(expect.objectContaining({ page: 1, pageSize: 20 }), { signal: expect.any(AbortSignal) });
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

  it("submits an idempotent evaluation report lifecycle command", async () => {
    const state = useLlmModelReleaseCenter();
    await state.load();
    await state.transitionReportLifecycle(77, "FREEZE", "retention review");

    expect(transitionLifecycle).toHaveBeenCalledWith(77, expect.objectContaining({
      action: "FREEZE",
      reason: "retention review",
      idempotencyKey: expect.stringContaining("freeze-77-")
    }));
  });

  it("loads, verifies and exports the bounded audit timeline", async () => {
    const state = useLlmModelReleaseCenter();
    await state.loadAudits(2);
    await state.verifyAudit(91);
    const exported = await state.exportAudits("csv");

    expect(loadAudits).toHaveBeenCalledWith(expect.objectContaining({ page: 2, pageSize: 20 }), { signal: expect.any(AbortSignal) });
    expect(verifyAudit).toHaveBeenCalledWith(91);
    expect(exportAudits).toHaveBeenCalledWith(expect.objectContaining({ format: "csv" }));
    expect(exported?.format).toBe("csv");
  });

  it("keeps successful release and runtime reads when the report query fails", async () => {
    loadReports.mockRejectedValueOnce(new Error("reports unavailable"));
    const state = useLlmModelReleaseCenter();
    await state.load();
    expect(state.center.value).toEqual(center());
    expect(state.reports.value).toEqual([]);
    expect(state.moduleStates.reports.error).toBe("reports unavailable");
    expect(state.moduleStates.center.error).toBe("");
    expect(state.moduleStates.runtimeMetrics.lastSuccessAt).not.toBe("");
  });

  it("keeps the most recently requested audit page when old responses arrive late", async () => {
    const first = deferred<Awaited<ReturnType<typeof fetchLlmModelReleaseAudits>>>();
    const second = deferred<Awaited<ReturnType<typeof fetchLlmModelReleaseAudits>>>();
    loadAudits.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const state = useLlmModelReleaseCenter();
    const older = state.loadAudits(1);
    const newer = state.loadAudits(2);
    second.resolve({ items: [], total: 41, hasMore: false }); await newer;
    first.resolve({ items: [], total: 40, hasMore: true }); await older;
    expect(state.auditPage.value).toBe(2);
    expect(state.auditTotal.value).toBe(41);
  });

  it.each(["center", "reports", "runtimeMetrics", "audits"] as const)(
    "retains other successful modules and marks old %s data after an isolated refresh failure", async key => {
      const state = useLlmModelReleaseCenter();
      await state.load();
      const previousTime = state.moduleStates[key].lastSuccessAt;
      const requests = { center: loadCenter, reports: loadReports, runtimeMetrics: loadRuntimeMetrics, audits: loadAudits };
      requests[key].mockRejectedValueOnce(new Error(`${key} unavailable`));
      state.trendDays.value = 7;
      await state.load();
      expect(state.center.value).not.toBeNull();
      expect(state.reports.value).toEqual([report()]);
      expect(state.moduleStates[key]).toMatchObject({ error: `${key} unavailable`, lastSuccessAt: previousTime, loading: false });
      for (const other of Object.keys(requests).filter(other => other !== key)) {
        expect(state.moduleStates[other as keyof typeof requests].error).toBe("");
      }
    }
  );

  it("does not promote or register shadow using reports left stale by a failed refresh", async () => {
    const state = useLlmModelReleaseCenter();
    await state.load(); state.releaseKey.value = "next";
    loadReports.mockRejectedValueOnce(new Error("reports unavailable")); await state.load();
    await state.registerShadow(); await state.promote();
    expect(registerShadow).not.toHaveBeenCalled(); expect(promote).not.toHaveBeenCalled();
    expect(state.errorMessage.value).toContain("刷新发布状态和评估报告");
  });

  it("keeps a newer audit request loading while an older cancelled request rejects", async () => {
    const first = deferred<Awaited<ReturnType<typeof fetchLlmModelReleaseAudits>>>();
    const second = deferred<Awaited<ReturnType<typeof fetchLlmModelReleaseAudits>>>();
    loadAudits.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const state = useLlmModelReleaseCenter();
    const older = state.loadAudits(1); const newer = state.loadAudits(2);
    expect(loadAudits.mock.calls[0]![1]!.signal!.aborted).toBe(true);
    first.reject(new Error("old failure")); await older;
    expect(state.auditLoading.value).toBe(true); expect(state.moduleStates.audits.error).toBe("");
    second.resolve({ items: [], total: 41, hasMore: false }); await newer;
    expect(state.auditLoading.value).toBe(false);
  });

  it("invalidates an in-flight audit when its filters change without a refresh", async () => {
    const pending = deferred<Awaited<ReturnType<typeof fetchLlmModelReleaseAudits>>>();
    loadAudits.mockReturnValueOnce(pending.promise);
    const state = useLlmModelReleaseCenter(); const request = state.loadAudits(1);
    state.auditOperator.value = "new-operator";
    expect(loadAudits.mock.calls[0]![1]!.signal!.aborted).toBe(true);
    pending.resolve({ items: [], total: 40, hasMore: true }); await request;
    expect(state.auditTotal.value).toBe(0);
    expect(state.moduleStates.audits.error).toContain("筛选条件已变更");
  });

  it("cancels all reads at scope disposal and ignores late responses", async () => {
    const pending = deferred<LlmModelReleaseCenter>();
    loadCenter.mockReturnValueOnce(pending.promise);
    const scope = effectScope(); const state = scope.run(useLlmModelReleaseCenter)!;
    const request = state.load(); scope.stop();
    expect(loadCenter.mock.calls[0]![1]!.signal!.aborted).toBe(true);
    pending.resolve(center()); await request;
    expect(state.center.value).toBeNull(); expect(state.loading.value).toBe(false);
    await state.load(); expect(loadCenter).toHaveBeenCalledTimes(1);
  });

  it("keeps the latest trend window when a slower previous refresh finishes", async () => {
    const old = deferred<LlmModelReleaseCenter>();
    loadCenter.mockReturnValueOnce(old.promise);
    const state = useLlmModelReleaseCenter(); const first = state.load();
    state.trendDays.value = 7;
    loadCenter.mockResolvedValueOnce({ ...center(), configuredModel: "latest-model" });
    await state.load(); old.resolve({ ...center(), configuredModel: "old-model" }); await first;
    expect(state.center.value?.configuredModel).toBe("latest-model");
    expect(state.moduleStates.center.lastSuccessLabel).toBe("7 天窗口");
  });
});

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

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
  createdBy: "tester",
  lifecycleStatus: "ACTIVE",
  retentionDays: 180,
  expiresAt: "2027-03-01T00:00:00",
  lifecycleVersion: 0
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

describe("selectedEvaluationSampleIds", () => {
  it("keeps formal evaluation separate from diagnostic selections", () => {
    expect(selectedEvaluationSampleIds(false, "case-1")).toBeUndefined();
  });
  it("deduplicates selections without adding other samples", () => {
    expect(selectedEvaluationSampleIds(true, "case-2,case-1\ncase-2，case-3"))
      .toEqual(["case-2", "case-1", "case-3"]);
  });
  it.each(["", "../private", "case/1", "x".repeat(129), Array.from({ length: 101 }, (_, i) => `case-${i}`).join(",")])(
    "rejects invalid sample selections: %s", text => {
      expect(() => selectedEvaluationSampleIds(true, text)).toThrow("有效样本 ID");
    }
  );
});
