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
      method: "GET",
      status: 200,
      result: "success",
      startedAtMs: 12,
      durationMs: 48
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
          method: "GET",
          status: 200,
          result: "success",
          startedAtMs: 12,
          durationMs: 48
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
      method: "POST",
      result: "success",
      startedAtMs: 5,
      durationMs: 30
    });

    await vi.advanceTimersByTimeAsync(1300);

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
