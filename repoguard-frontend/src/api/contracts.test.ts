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
});

const setCsrfCookie = (token: string) => {
  document.cookie = `repoguard_csrf_token=${encodeURIComponent(token)}; path=/`;
};

const clearCsrfCookie = () => {
  document.cookie = "repoguard_csrf_token=; Max-Age=0; path=/";
};
