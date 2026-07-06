import { afterEach, describe, expect, it, vi } from "vitest";
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
    vi.clearAllMocks();
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
    expect(dashboardApi.fetchDashboardLlmQuality).toHaveBeenCalledWith(7);
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
    expect(dashboard.errorMessage.value).toBe("trend unavailable");
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
