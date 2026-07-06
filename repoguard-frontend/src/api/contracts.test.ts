import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "./contracts";

const okResponse = (data: unknown) =>
  new Response(JSON.stringify({
    success: true,
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-02T18:00:00+08:00"
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("apiRequest", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    clearCsrfCookie();
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  it("builds query parameters from the typed operation contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({ items: [], total: 0 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiRequest("fetchReviews", {
      page: 2,
      pageSize: 20,
      repository: "repo",
      status: "completed",
      riskLevel: "high",
      source: undefined,
      triggerSource: undefined,
      keyword: ""
    });

    expect(result).toEqual({ items: [], total: 0 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/reviews");
    expect(url).toContain("page=2");
    expect(url).toContain("pageSize=20");
    expect(url).toContain("repository=repo");
    expect(url).toContain("status=completed");
    expect(url).toContain("riskLevel=high");
    expect(url).not.toContain("keyword=");
    expect(init.method).toBeUndefined();
  });

  it("serializes request bodies from the typed operation contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({
      taskId: 9,
      status: "queued",
      message: "queued",
      publishAttempts: 0
    }));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("triggerManualReview", {
      organization: "repo-guard",
      repository: "agent",
      prNumber: 9,
      title: "manual review",
      commit: "abc123",
      branch: "main"
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/reviews/manual");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({
      organization: "repo-guard",
      repository: "agent",
      prNumber: 9,
      title: "manual review",
      commit: "abc123",
      branch: "main"
    }));
  });

  it("adds the CSRF header for cookie-backed logout", async () => {
    setCsrfCookie("logout-csrf-token");
    const fetchMock = vi.fn().mockResolvedValue(okResponse(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("logout", undefined);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/auth/logout");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({}));
    expect(new Headers(init.headers).get("X-RepoGuard-CSRF")).toBe("logout-csrf-token");
  });

  it("posts secret re-encryption requests to the protected system config endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({
      executed: false,
      scannedCount: 1,
      reEncryptedCount: 1,
      skippedCount: 0,
      failedCount: 0,
      items: []
    }));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("reEncryptSecrets", {
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "old-key",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key",
      execute: false
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/config/secrets/re-encryption");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({
      sourceEncryptionKey: "source-secret",
      sourceKeyId: "old-key",
      targetEncryptionKey: "target-secret",
      targetKeyId: "new-key",
      execute: false
    }));
  });

  it("keeps review detail heavy sections on explicit paged endpoint queries", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(okResponse({ items: [], total: 0 })));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchReviewFindings", {
      id: 42,
      page: 3,
      pageSize: 20,
      severity: "high",
      category: "security",
      feedbackStatus: "pending"
    });
    await apiRequest("fetchReviewChangedFiles", {
      id: 42,
      page: 2,
      pageSize: 20,
      hasFinding: false
    });
    await apiRequest("fetchReviewMissingTests", {
      id: 42,
      page: 4,
      pageSize: 20
    });
    await apiRequest("fetchGithubCommentPreview", {
      id: 42,
      page: 5,
      pageSize: 10,
      commentableOnly: true
    });
    await apiRequest("fetchGithubCommentPublicationHistory", {
      id: 42,
      page: 2,
      pageSize: 10,
      status: "failed"
    });

    const urls = fetchMock.mock.calls.map(([url]) => String(url));
    expect(urls[0]).toContain("/api/v1/reviews/42/findings");
    expect(urls[0]).toContain("page=3");
    expect(urls[0]).toContain("pageSize=20");
    expect(urls[0]).toContain("severity=high");
    expect(urls[0]).toContain("category=security");
    expect(urls[0]).toContain("feedbackStatus=pending");
    expect(urls[1]).toContain("/api/v1/reviews/42/changed-files");
    expect(urls[1]).toContain("page=2");
    expect(urls[1]).toContain("pageSize=20");
    expect(urls[1]).toContain("hasFinding=false");
    expect(urls[2]).toContain("/api/v1/reviews/42/missing-tests");
    expect(urls[2]).toContain("page=4");
    expect(urls[2]).toContain("pageSize=20");
    expect(urls[3]).toContain("/api/v1/reviews/42/github-comments/preview");
    expect(urls[3]).toContain("page=5");
    expect(urls[3]).toContain("pageSize=10");
    expect(urls[3]).toContain("commentableOnly=true");
    expect(urls[4]).toContain("/api/v1/reviews/42/github-comments/publications");
    expect(urls[4]).toContain("page=2");
    expect(urls[4]).toContain("pageSize=10");
    expect(urls[4]).toContain("status=failed");
  });

  it("keeps notification ops, message queue, and notification center endpoint contracts", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(okResponse({ items: [], total: 0 })));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchNotificationBindings", undefined);
    await apiRequest("fetchNotificationEvents", {
      page: 3,
      pageSize: 5,
      status: "failed",
      taskId: 512
    });
    await apiRequest("retryNotificationEvent", { id: 2001 });
    await apiRequest("fetchNotificationDeliveries", {
      page: 4,
      pageSize: 15,
      status: "failed",
      taskId: 512
    });
    await apiRequest("fetchMessageQueueHealth", undefined);
    await apiRequest("requeueMessageQueueTask", { taskId: 42 });
    await apiRequest("fetchNotifications", undefined);

    const calls = fetchMock.mock.calls as [string, RequestInit][];
    expect(calls[0][0]).toContain("/api/v1/config/notification-bindings");
    expect(calls[0][0]).toContain("page=1");
    expect(calls[0][0]).toContain("pageSize=100");
    expect(calls[0][1].method).toBeUndefined();
    expect(calls[1][0]).toContain("/api/v1/notification-events");
    expect(calls[1][0]).toContain("page=3");
    expect(calls[1][0]).toContain("pageSize=5");
    expect(calls[1][0]).toContain("status=failed");
    expect(calls[1][0]).toContain("taskId=512");
    expect(calls[2][0]).toContain("/api/v1/notification-events/2001/retry");
    expect(calls[2][1].method).toBe("POST");
    expect(calls[3][0]).toContain("/api/v1/notification-deliveries");
    expect(calls[3][0]).toContain("page=4");
    expect(calls[3][0]).toContain("pageSize=15");
    expect(calls[3][0]).toContain("status=failed");
    expect(calls[3][0]).toContain("taskId=512");
    expect(calls[4][0]).toContain("/api/v1/message-queue/health");
    expect(calls[4][1].method).toBeUndefined();
    expect(calls[5][0]).toContain("/api/v1/message-queue/tasks/42/requeue");
    expect(calls[5][1].method).toBe("POST");
    expect(calls[6][0]).toContain("/api/v1/notifications");
    expect(calls[6][1].method).toBeUndefined();
  });

  it("keeps dashboard overview and split module endpoint contracts", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(okResponse([])));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchDashboardOverview", { llmTrendDays: 30 });
    await apiRequest("fetchDashboardSummary", undefined);
    await apiRequest("fetchDashboardReviewTrend", undefined);
    await apiRequest("fetchDashboardRiskDistribution", undefined);
    await apiRequest("fetchDashboardRules", undefined);
    await apiRequest("fetchDashboardHighRiskReviews", undefined);
    await apiRequest("fetchDashboardLlmQuality", { llmTrendDays: 90 });
    await apiRequest("fetchSystemHealthSummary", undefined);

    const calls = fetchMock.mock.calls as [string, RequestInit][];
    expect(calls[0][0]).toContain("/api/v1/dashboard/overview");
    expect(calls[0][0]).toContain("llmTrendDays=30");
    expect(calls[1][0]).toContain("/api/v1/dashboard/summary");
    expect(calls[2][0]).toContain("/api/v1/dashboard/review-trend");
    expect(calls[3][0]).toContain("/api/v1/dashboard/risk-distribution");
    expect(calls[4][0]).toContain("/api/v1/dashboard/rules");
    expect(calls[5][0]).toContain("/api/v1/dashboard/high-risk-reviews");
    expect(calls[6][0]).toContain("/api/v1/dashboard/llm-quality");
    expect(calls[6][0]).toContain("llmTrendDays=90");
    expect(calls[7][0]).toContain("/api/v1/system/health/summary");
    expect(calls.every(([, init]) => init.method === undefined)).toBe(true);
  });
});

const setCsrfCookie = (token: string) => {
  document.cookie = `repoguard_csrf_token=${encodeURIComponent(token)}; path=/`;
};

const clearCsrfCookie = () => {
  document.cookie = "repoguard_csrf_token=; Max-Age=0; path=/";
};
