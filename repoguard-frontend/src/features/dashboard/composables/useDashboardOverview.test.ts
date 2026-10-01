import { afterEach, describe, expect, it, vi } from "vitest";
import { effectScope } from "vue";
import { useDashboardOverview } from "./useDashboardOverview";
import type {
  DashboardLlmQuality,
  DashboardMetric,
  DashboardRules,
  HighRiskReview,
  ReviewTrendPoint,
  SystemHealthItem
} from "@/types";

const dashboardApi = vi.hoisted(() => ({
  fetchDashboardOverview: vi.fn(),
  fetchDashboardSummary: vi.fn(),
  fetchDashboardReviewTrend: vi.fn(),
  fetchDashboardRiskDistribution: vi.fn(),
  fetchDashboardRules: vi.fn(),
  fetchDashboardHighRiskReviews: vi.fn(),
  fetchDashboardLlmQuality: vi.fn(),
  fetchSystemHealthSummary: vi.fn()
}));

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  warning: vi.fn()
}));

vi.mock("@/api/dashboard", () => dashboardApi);
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: messages
}));

describe("useDashboardOverview", () => {
  afterEach(() => {
    vi.resetAllMocks();
  });

  it("loads the dashboard through split endpoints without calling the monolithic overview API", async () => {
    mockSuccessfulDashboardApis();

    const dashboard = useDashboardOverview();
    await dashboard.loadOverview();
    await flushAsync();

    expect(dashboardApi.fetchDashboardOverview).not.toHaveBeenCalled();
    expect(dashboardApi.fetchDashboardSummary).toHaveBeenCalledTimes(1);
    expect(dashboardApi.fetchDashboardReviewTrend).toHaveBeenCalledTimes(1);
    expect(dashboardApi.fetchDashboardRiskDistribution).toHaveBeenCalledTimes(1);
    expect(dashboardApi.fetchDashboardRules).toHaveBeenCalledTimes(1);
    expect(dashboardApi.fetchDashboardHighRiskReviews).toHaveBeenCalledTimes(1);
    expect(dashboardApi.fetchDashboardLlmQuality).toHaveBeenCalledWith(7, { signal: expect.any(AbortSignal) });
    expect(dashboardApi.fetchSystemHealthSummary).toHaveBeenCalledTimes(1);
    expect(dashboard.loading.value).toBe(false);
    expect(dashboard.moduleLoading.value).toBe(false);
    expect(dashboard.healthLoading.value).toBe(false);
    expect(dashboard.overviewMetrics.value).toEqual(summaryMetrics);
    expect(dashboard.reviewTrend.value).toEqual(reviewTrend);
    expect(dashboard.riskDistribution.value).toEqual(riskDistribution);
    expect(dashboard.ruleHits.value).toEqual(rules.ruleHits);
    expect(dashboard.highRiskReviews.value).toEqual(highRiskReviews);
    expect(dashboard.failedRules.value).toEqual(rules.failedRules);
    expect(dashboard.llmQualityTrend.value).toEqual(llmQuality.trend);
    expect(dashboard.systemHealth.value).toEqual(systemHealth);
    expect(messages.error).not.toHaveBeenCalled();
    expect(messages.warning).not.toHaveBeenCalled();
  });

  it("keeps the summary metrics when a secondary dashboard module fails", async () => {
    dashboardApi.fetchDashboardSummary.mockResolvedValue(summaryMetrics);
    dashboardApi.fetchDashboardReviewTrend.mockRejectedValue(new Error("trend unavailable"));
    dashboardApi.fetchDashboardRiskDistribution.mockResolvedValue(riskDistribution);
    dashboardApi.fetchDashboardRules.mockResolvedValue(rules);
    dashboardApi.fetchDashboardHighRiskReviews.mockResolvedValue(highRiskReviews);
    dashboardApi.fetchDashboardLlmQuality.mockResolvedValue(llmQuality);
    dashboardApi.fetchSystemHealthSummary.mockResolvedValue(systemHealth);

    const dashboard = useDashboardOverview();
    await dashboard.loadOverview();
    await flushAsync();

    expect(dashboardApi.fetchDashboardOverview).not.toHaveBeenCalled();
    expect(dashboard.overviewMetrics.value).toEqual(summaryMetrics);
    expect(dashboard.reviewTrend.value).toEqual([]);
    expect(dashboard.riskDistribution.value).toEqual(riskDistribution);
    expect(dashboard.ruleHits.value).toEqual(rules.ruleHits);
    expect(dashboard.loading.value).toBe(false);
    expect(dashboard.moduleLoading.value).toBe(false);
    expect(dashboard.healthLoading.value).toBe(false);
    expect(messages.error).toHaveBeenCalledWith("trend unavailable");
  });

  it("ignores stale dashboard module responses after a newer refresh", async () => {
    const staleReviewTrend = deferred<ReviewTrendPoint[]>();
    const freshReviewTrend: ReviewTrendPoint[] = [
      {
        date: "07-07",
        value: 28
      }
    ];
    dashboardApi.fetchDashboardSummary.mockResolvedValue(summaryMetrics);
    dashboardApi.fetchDashboardReviewTrend
      .mockReturnValueOnce(staleReviewTrend.promise)
      .mockResolvedValueOnce(freshReviewTrend);
    dashboardApi.fetchDashboardRiskDistribution.mockResolvedValue(riskDistribution);
    dashboardApi.fetchDashboardRules.mockResolvedValue(rules);
    dashboardApi.fetchDashboardHighRiskReviews.mockResolvedValue(highRiskReviews);
    dashboardApi.fetchDashboardLlmQuality.mockResolvedValue(llmQuality);
    dashboardApi.fetchSystemHealthSummary.mockResolvedValue(systemHealth);

    const dashboard = useDashboardOverview();
    await dashboard.loadOverview();
    await dashboard.loadOverview();
    await flushAsync();

    expect(dashboard.reviewTrend.value).toEqual(freshReviewTrend);

    staleReviewTrend.resolve(reviewTrend);
    await flushAsync();

    expect(dashboard.reviewTrend.value).toEqual(freshReviewTrend);
    expect(dashboard.moduleLoading.value).toBe(false);
  });

  it("presents successful modules before a slow sibling completes", async () => {
    mockSuccessfulDashboardApis();
    const slowTrend = deferred<ReviewTrendPoint[]>();
    dashboardApi.fetchDashboardReviewTrend.mockReturnValueOnce(slowTrend.promise);
    const dashboard = useDashboardOverview();
    const pending = dashboard.loadDashboardModules();
    await flushAsync();
    expect(dashboard.riskDistribution.value).toEqual(riskDistribution);
    expect(dashboard.ruleHits.value).toEqual(rules.ruleHits);
    slowTrend.resolve(reviewTrend);
    await pending;
  });

  it("uses one quality version across deferred loading and 7 to 30 to 7 changes", async () => {
    mockSuccessfulDashboardApis();
    const oldSeven = deferred<DashboardLlmQuality>();
    const freshSeven = { ...llmQuality, trend: [{ ...llmQuality.trend[0]!, taskCount: 7 }] };
    dashboardApi.fetchDashboardLlmQuality.mockReturnValueOnce(oldSeven.promise)
      .mockResolvedValueOnce({ ...llmQuality, trend: [{ ...llmQuality.trend[0]!, taskCount: 30 }] })
      .mockResolvedValueOnce(freshSeven);
    const dashboard = useDashboardOverview();
    const oldDeferred = dashboard.loadDeferredModules();
    dashboard.updateLlmTrendDays(30);
    dashboard.updateLlmTrendDays(7);
    await flushAsync();
    expect(dashboard.llmQualityTrend.value).toEqual(freshSeven.trend);
    oldSeven.resolve(llmQuality);
    await oldDeferred;
    expect(dashboard.llmQualityTrend.value).toEqual(freshSeven.trend);
    expect(dashboard.highRiskReviews.value).toEqual(highRiskReviews);
  });

  it("ignores responses and errors after the owning scope is disposed", async () => {
    mockSuccessfulDashboardApis();
    const lateSummary = deferred<DashboardMetric[]>();
    dashboardApi.fetchDashboardSummary.mockReturnValueOnce(lateSummary.promise);
    const scope = effectScope();
    const dashboard = scope.run(() => useDashboardOverview())!;
    const pending = dashboard.loadOverview();
    scope.stop();
    lateSummary.resolve(summaryMetrics);
    await pending;
    await flushAsync();
    expect(dashboard.overviewMetrics.value).toEqual([]);
    expect(dashboardApi.fetchDashboardReviewTrend).not.toHaveBeenCalled();
    expect(messages.error).not.toHaveBeenCalled();
  });

  it("keeps failed module data and its last successful time and window", async () => {
    mockSuccessfulDashboardApis();
    const dashboard = useDashboardOverview();
    await dashboard.loadDeferredModules();
    const successfulAt = dashboard.moduleStates.llmQuality.lastSuccessAt;
    expect(successfulAt).not.toBe("");
    dashboardApi.fetchDashboardLlmQuality.mockRejectedValueOnce(new Error("quality unavailable"));
    dashboard.updateLlmTrendDays(30);
    await flushAsync();
    expect(dashboard.llmQualityTrend.value).toEqual(llmQuality.trend);
    expect(dashboard.moduleStates.llmQuality.lastSuccessAt).toBe(successfulAt);
    expect(dashboard.moduleStates.llmQuality.lastSuccessLabel).toBe("7 天窗口");
    expect(dashboard.moduleStates.llmQuality.error).toBe("quality unavailable");
    expect(dashboard.moduleStates.llmQuality.loading).toBe(false);
  });

  it("does not discard high-risk reviews when the quality endpoint fails", async () => {
    mockSuccessfulDashboardApis();
    dashboardApi.fetchDashboardLlmQuality.mockRejectedValueOnce(new Error("quality unavailable"));
    const dashboard = useDashboardOverview();
    await dashboard.loadDeferredModules();
    expect(dashboard.highRiskReviews.value).toEqual(highRiskReviews);
    expect(dashboard.moduleStates.highRiskReviews.error).toBe("");
    expect(dashboard.moduleStates.llmQuality.error).toBe("quality unavailable");
    expect(dashboard.errorMessage.value).toBe("");
  });

  it("cancels replaced requests without cancelling unrelated modules", async () => {
    mockSuccessfulDashboardApis();
    const slowTrend = deferred<ReviewTrendPoint[]>();
    const oldQuality = deferred<DashboardLlmQuality>();
    dashboardApi.fetchDashboardReviewTrend.mockReturnValueOnce(slowTrend.promise);
    dashboardApi.fetchDashboardLlmQuality.mockReturnValueOnce(oldQuality.promise);
    const dashboard = useDashboardOverview();
    const primary = dashboard.loadDashboardModules();
    const quality = dashboard.loadLlmQuality();
    const trendSignal = dashboardApi.fetchDashboardReviewTrend.mock.calls[0]![0].signal as AbortSignal;
    const oldQualitySignal = dashboardApi.fetchDashboardLlmQuality.mock.calls[0]![1].signal as AbortSignal;
    await dashboard.loadSystemHealth();
    dashboard.updateLlmTrendDays(30);
    await flushAsync();
    expect(trendSignal.aborted).toBe(false);
    expect(oldQualitySignal.aborted).toBe(true);
    slowTrend.resolve(reviewTrend);
    oldQuality.resolve(llmQuality);
    await Promise.all([primary, quality]);
    expect(dashboard.reviewTrend.value).toEqual(reviewTrend);
    expect(dashboard.moduleStates.llmQuality.lastSuccessLabel).toBe("30 天窗口");
  });

  it("suppresses errors from requests that resolve after disposal", async () => {
    mockSuccessfulDashboardApis();
    const lateHealth = deferred<SystemHealthItem[]>();
    dashboardApi.fetchSystemHealthSummary.mockReturnValueOnce(lateHealth.promise);
    const scope = effectScope();
    const dashboard = scope.run(() => useDashboardOverview())!;
    const pending = dashboard.loadSystemHealth();
    const signal = dashboardApi.fetchSystemHealthSummary.mock.calls[0]![0].signal as AbortSignal;
    scope.stop();
    lateHealth.reject(new Error("late failure"));
    await pending;
    expect(signal.aborted).toBe(true);
    expect(dashboard.moduleStates.systemHealth.error).toBe("");
    expect(messages.warning).not.toHaveBeenCalled();
  });
});

const summaryMetrics: DashboardMetric[] = [
  {
    label: "Weekly reviews",
    value: "12",
    trend: "20%",
    trendType: "up",
    color: "blue"
  }
];

const reviewTrend: ReviewTrendPoint[] = [
  {
    date: "07-06",
    value: 12
  }
];

const riskDistribution = [
  {
    name: "High",
    value: 2,
    color: "#ef4444",
    percent: "20%"
  }
];

const rules: DashboardRules = {
  ruleHits: [
    {
      name: "Controller tests",
      value: 3,
      color: "#14b8a6",
      percent: "30%"
    }
  ],
  failedRules: [
    {
      name: "Controller tests",
      count: 3,
      trend: "5%",
      direction: "up",
      percent: "30%"
    }
  ]
};

const highRiskReviews: HighRiskReview[] = [
  {
    title: "Tighten auth checks",
    repository: "repo-guard",
    riskLevel: "high",
    ruleHits: 3,
    reviewedAt: "2026-07-06T10:00:00+08:00",
    status: "completed"
  }
];

const llmQuality: DashboardLlmQuality = {
  byModel: [],
  byRepository: [],
  trend: [
    {
      date: "07-06",
      taskCount: 4,
      parseSuccessRate: "100%",
      fallbackRate: "0%",
      partialFallbackRate: "0%"
    }
  ]
};

const systemHealth: SystemHealthItem[] = [
  {
    name: "MySQL",
    status: "UP"
  }
];

const mockSuccessfulDashboardApis = () => {
  dashboardApi.fetchDashboardSummary.mockResolvedValue(summaryMetrics);
  dashboardApi.fetchDashboardReviewTrend.mockResolvedValue(reviewTrend);
  dashboardApi.fetchDashboardRiskDistribution.mockResolvedValue(riskDistribution);
  dashboardApi.fetchDashboardRules.mockResolvedValue(rules);
  dashboardApi.fetchDashboardHighRiskReviews.mockResolvedValue(highRiskReviews);
  dashboardApi.fetchDashboardLlmQuality.mockResolvedValue(llmQuality);
  dashboardApi.fetchSystemHealthSummary.mockResolvedValue(systemHealth);
};

const flushAsync = () => new Promise(resolve => window.setTimeout(resolve, 0));

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
};
