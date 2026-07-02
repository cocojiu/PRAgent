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
});
