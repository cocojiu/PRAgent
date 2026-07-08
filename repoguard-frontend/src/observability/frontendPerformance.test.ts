import { afterEach, describe, expect, it, vi } from "vitest";

const okResponse = () =>
  new Response(JSON.stringify({
    success: true,
    code: "OK",
    message: "OK",
    data: null,
    timestamp: "2026-07-05T23:50:00+08:00"
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("frontend performance observation", () => {
  afterEach(async () => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.resetModules();
    const client = await import("@/api/client");
    client.clearAuthToken();
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  it("flushes initial api waterfall observations", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);
    const client = await import("@/api/client");
    const observation = await import("./frontendPerformance");
    client.saveAuthTokens("access-token", "refresh-token", true);

    observation.startFrontendPerformanceObservation(() => "overview");
    observation.observeFrontendApiRequest({
      operation: "fetchDashboardSummary",
      path: "/api/v1/dashboard/summary",
      method: "GET",
      status: 200,
      result: "success",
      startedAtMs: 12,
      durationMs: 48
    });
    const buffer = await import("./frontendPerformanceBuffer");
    buffer.observeFrontendLongTask({
      startedAtMs: 80,
      durationMs: 24,
      region: "review-detail.findings",
      operation: "fetchReviewFindings",
      itemCount: 20,
      totalCount: 300
    });

    await vi.advanceTimersByTimeAsync(1300);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/observability/frontend/performance");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-token");
    expect(JSON.parse(String(init.body))).toMatchObject({
      route: "overview",
      apiRequests: [
        {
          operation: "fetchDashboardSummary",
          path: "/api/v1/dashboard/summary",
          method: "GET",
          status: 200,
          result: "success",
          startedAtMs: 12,
          durationMs: 48
        }
      ],
      longTasks: [
        {
          startedAtMs: 80,
          durationMs: 24,
          region: "review-detail.findings",
          operation: "fetchReviewFindings",
          itemCount: 20,
          totalCount: 300
        }
      ]
    });
  });

  it("does not send observations before authentication is available", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);
    const observation = await import("./frontendPerformance");

    observation.startFrontendPerformanceObservation(() => "login");
    observation.observeFrontendApiRequest({
      operation: "login",
      path: "/api/v1/auth/login",
      method: "POST",
      result: "success",
      startedAtMs: 5,
      durationMs: 30
    });

    await vi.advanceTimersByTimeAsync(1300);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("keeps lazy detail render observations after the initial window", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);
    const client = await import("@/api/client");
    const observation = await import("./frontendPerformance");
    const buffer = await import("./frontendPerformanceBuffer");
    client.saveAuthTokens("access-token", "refresh-token", true);

    observation.startFrontendPerformanceObservation(() => "review-detail");
    await vi.advanceTimersByTimeAsync(7000);
    observation.observeFrontendApiRequest({
      operation: "fetchGithubCommentPreview",
      path: "/api/v1/reviews/{id}/github-comments/preview",
      method: "GET",
      status: 200,
      result: "success",
      traceId: "trace-preview-1",
      responseBytes: 40960,
      startedAtMs: 7000,
      durationMs: 42
    });
    buffer.observeFrontendLongTask({
      startedAtMs: 7050,
      durationMs: 18,
      region: "review-detail.comment-preview",
      operation: "fetchGithubCommentPreview",
      itemCount: 20,
      totalCount: 260
    });
    observation.observeFrontendApiRequest({
      operation: "lateApi",
      path: "/api/v1/late",
      method: "GET",
      result: "success",
      startedAtMs: 7020,
      durationMs: 25
    });

    await vi.advanceTimersByTimeAsync(1300);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toMatchObject({
      route: "review-detail",
      apiRequests: [],
      longTasks: [
        {
          region: "review-detail.comment-preview",
          operation: "fetchGithubCommentPreview",
          itemCount: 20,
          totalCount: 260,
          apiPath: "/api/v1/reviews/{id}/github-comments/preview",
          apiTraceId: "trace-preview-1",
          apiResponseBytes: 40960,
          apiDurationMs: 42
        }
      ]
    });
  });
});
