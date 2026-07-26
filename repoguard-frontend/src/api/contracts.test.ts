import { afterEach, describe, expect, it, vi } from "vitest";

const frontendPerformance = vi.hoisted(() => ({
  observeFrontendApiRequest: vi.fn()
}));

vi.mock("@/observability/frontendPerformanceBuffer", () => frontendPerformance);

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
    frontendPerformance.observeFrontendApiRequest.mockClear();
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
      keyword: "",
      cursorCreatedAt: "2026-07-08 12:00:00",
      cursorId: 123,
      totalHint: 42
    });

    expect(result).toEqual({ items: [], total: 0 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/reviews");
    expect(url).toContain("page=2");
    expect(url).toContain("pageSize=20");
    expect(url).toContain("repository=repo");
    expect(url).toContain("status=completed");
    expect(url).toContain("riskLevel=high");
    expect(url).toContain("cursorCreatedAt=2026-07-08+12%3A00%3A00");
    expect(url).toContain("cursorId=123");
    expect(url).toContain("totalHint=42");
    expect(url).not.toContain("keyword=");
    expect(init.method).toBeUndefined();
  });

  it("builds review summary query parameters from the shared list filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({
      total: 260,
      highRisk: 13,
      failed: 26,
      averageDurationSeconds: 95
    }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiRequest("fetchReviewListSummary", {
      repository: "repo",
      status: "failed",
      riskLevel: "high",
      source: undefined,
      triggerSource: "github_webhook",
      keyword: ""
    });

    expect(result).toEqual({ total: 260, highRisk: 13, failed: 26, averageDurationSeconds: 95 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/reviews/summary");
    expect(url).toContain("repository=repo");
    expect(url).toContain("status=failed");
    expect(url).toContain("riskLevel=high");
    expect(url).toContain("triggerSource=github_webhook");
    expect(url).not.toContain("keyword=");
    expect(init.method).toBeUndefined();
  });

  it("forwards cancellation signals through the typed API contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({}));
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    await apiRequest("fetchMessageQueueHealth", undefined, { signal: controller.signal });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBe(controller.signal);
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
      commit: "0123456789abcdef0123456789abcdef01234567",
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
      commit: "0123456789abcdef0123456789abcdef01234567",
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

  it("keeps operational cache, data retention, and refresh reset endpoint contracts", async () => {
    const responseData = [
      {},
      {},
      {},
      {
        accessToken: "test-access-token-value",
        tokenType: "Bearer",
        accessTokenExpiresInSeconds: 900,
        refreshTokenExpiresInSeconds: 604800,
        user: { id: 1, username: "admin", email: "admin@example.com", role: "ADMIN" }
      }
    ];
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(okResponse(responseData.shift() ?? {})));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchCacheStats", undefined);
    await apiRequest("cleanupDataRetention", {
      retentionDays: 90,
      maxTasks: 500,
      execute: false,
      backupReference: "backup://mysql/prod/2026-07-07T22:00:00",
      confirmText: "DRY_RUN"
    });
    await apiRequest("fetchDataRetentionCleanupAudits", {
      page: 2,
      pageSize: 50,
      mode: "execute",
      status: "completed",
      backupReference: "backup://mysql/prod/2026-07-07T22:00:00"
    });
    await apiRequest("resetRefreshToken", {
      account: "admin",
      password: "password",
      remember: true
    });

    const calls = fetchMock.mock.calls as [string, RequestInit][];
    expect(calls[0][0]).toContain("/api/v1/cache/stats");
    expect(calls[0][1].method).toBeUndefined();
    expect(calls[1][0]).toContain("/api/v1/config/data-retention/cleanup");
    expect(calls[1][1].method).toBe("POST");
    expect(calls[1][1].body).toBe(JSON.stringify({
      retentionDays: 90,
      maxTasks: 500,
      execute: false,
      backupReference: "backup://mysql/prod/2026-07-07T22:00:00",
      confirmText: "DRY_RUN"
    }));
    expect(calls[2][0]).toContain("/api/v1/config/data-retention/cleanup-audits");
    expect(calls[2][0]).toContain("page=2");
    expect(calls[2][0]).toContain("pageSize=50");
    expect(calls[2][0]).toContain("mode=execute");
    expect(calls[2][0]).toContain("status=completed");
    expect(calls[2][0]).toContain("backupReference=backup%3A%2F%2Fmysql%2Fprod%2F2026-07-07T22%3A00%3A00");
    expect(calls[2][1].method).toBeUndefined();
    expect(calls[3][0]).toContain("/api/v1/auth/refresh-token/reset");
    expect(calls[3][1].method).toBe("POST");
    expect(calls[3][1].body).toBe(JSON.stringify({
      account: "admin",
      password: "password",
      remember: true
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

    await apiRequest("fetchNotificationBindings", { page: 2, pageSize: 25, provider: "lark" });
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
    expect(calls[0][0]).toContain("page=2");
    expect(calls[0][0]).toContain("pageSize=25");
    expect(calls[0][0]).toContain("provider=lark");
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
    expect(frontendPerformance.observeFrontendApiRequest).toHaveBeenNthCalledWith(2, expect.objectContaining({
      operation: "fetchDashboardSummary",
      path: "/api/v1/dashboard/summary",
      method: "GET",
      result: "success"
    }));
    expect(frontendPerformance.observeFrontendApiRequest).toHaveBeenNthCalledWith(7, expect.objectContaining({
      operation: "fetchDashboardLlmQuality",
      path: "/api/v1/dashboard/llm-quality",
      method: "GET",
      result: "success"
    }));
  });

  it("posts frontend performance reports through the typed contract without self-observation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("reportFrontendPerformance", {
      route: "overview",
      apiRequests: [{
        operation: "fetchDashboardSummary",
        path: "/api/v1/dashboard/summary",
        method: "GET",
        status: 200,
        result: "success",
        startedAtMs: 12,
        durationMs: 48
      }],
      longTasks: []
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/api/v1/observability/frontend/performance");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({
      route: "overview",
      apiRequests: [{
        operation: "fetchDashboardSummary",
        path: "/api/v1/dashboard/summary",
        method: "GET",
        status: 200,
        result: "success",
        startedAtMs: 12,
        durationMs: 48
      }],
      longTasks: []
    }));
    expect(frontendPerformance.observeFrontendApiRequest).not.toHaveBeenCalled();
  });

  it("reports low-cardinality observation paths for dynamic endpoints", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({ items: [], total: 0 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchReviewFindings", {
      id: 521,
      page: 1,
      pageSize: 20
    });

    expect(frontendPerformance.observeFrontendApiRequest).toHaveBeenCalledWith(expect.objectContaining({
      operation: "fetchReviewFindings",
      path: "/api/v1/reviews/{id}/findings",
      method: "GET",
      result: "success"
    }));
  });

  it("rejects malformed critical responses and reports the contract failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okResponse({ accessToken: "incomplete" })));

    await expect(apiRequest("login", {
      account: "admin",
      password: "secret",
      remember: false
    })).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
      status: 200
    });
    expect(frontendPerformance.observeFrontendApiRequest).toHaveBeenCalledWith(expect.objectContaining({
      operation: "login",
      result: "failed",
      status: 200
    }));
  });

  it("keeps system integration and review rule endpoint contracts", async () => {
    const integrationResponse = { provider: "integration", status: "configured", baseUrl: "https://example.com" };
    const responseData = [
      integrationResponse,
      integrationResponse,
      integrationResponse,
      integrationResponse,
      integrationResponse,
      integrationResponse,
      {
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
      },
      {
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
      }
    ];
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(okResponse(responseData.shift() ?? {})));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("fetchGithubIntegrationConfig", undefined);
    await apiRequest("updateGithubIntegrationConfig", {
      baseUrl: "https://api.github.com",
      token: "github-token",
      defaultOwner: "repo-guard",
      defaultRepo: "agent"
    });
    await apiRequest("fetchMysqlIntegrationConfig", undefined);
    await apiRequest("updateMysqlIntegrationConfig", {
      baseUrl: "jdbc:mysql://localhost:3306/repoguard",
      username: "repoguard",
      secret: "mysql-secret",
      resource: "repoguard"
    });
    await apiRequest("fetchRabbitMqIntegrationConfig", undefined);
    await apiRequest("updateRabbitMqIntegrationConfig", {
      baseUrl: "amqp://localhost:5672",
      username: "guest",
      secret: "rabbit-secret",
      resource: "/"
    });
    await apiRequest("fetchReviewPolicyConfig", undefined);
    await apiRequest("updateReviewPolicyConfig", {
      llmEnabled: true,
      llmProvider: "openai",
      modelName: "gpt-4.1",
      apiKey: "llm-key",
      baseUrl: "https://api.openai.com/v1",
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
    await apiRequest("fetchReviewRules", undefined);
    await apiRequest("createReviewRule", {
      id: "RG-JAVA-001",
      name: "Java rule",
      scope: "backend",
      applicableLanguages: "java",
      filePatterns: "**/*.java",
      severity: "high",
      status: "enabled",
      confidence: 0.8,
      description: "Detect risky Java changes",
      positiveExample: "Use parameterized SQL",
      falsePositiveGuidance: "Ignore generated code"
    });
    await apiRequest("updateReviewRule", {
      id: "RG-JAVA-001",
      payload: {
        id: "RG-JAVA-001",
        name: "Java rule",
        scope: "backend",
        applicableLanguages: "java",
        filePatterns: "**/*.java",
        severity: "high",
        status: "enabled",
        confidence: 0.8,
        description: "Detect risky Java changes",
        positiveExample: "Use parameterized SQL",
        falsePositiveGuidance: "Ignore generated code"
      }
    });
    await apiRequest("updateReviewRuleStatus", {
      id: "RG-JAVA-001",
      payload: { status: "disabled" }
    });

    const calls = fetchMock.mock.calls as [string, RequestInit][];
    expect(calls[0][0]).toContain("/api/v1/config/integrations/github");
    expect(calls[0][1].method).toBeUndefined();
    expect(calls[1][0]).toContain("/api/v1/config/integrations/github");
    expect(calls[1][1].method).toBe("PUT");
    expect(calls[1][1].body).toBe(JSON.stringify({
      baseUrl: "https://api.github.com",
      token: "github-token",
      defaultOwner: "repo-guard",
      defaultRepo: "agent"
    }));
    expect(calls[2][0]).toContain("/api/v1/config/integrations/mysql");
    expect(calls[2][1].method).toBeUndefined();
    expect(calls[3][0]).toContain("/api/v1/config/integrations/mysql");
    expect(calls[3][1].method).toBe("PUT");
    expect(calls[4][0]).toContain("/api/v1/config/integrations/rabbitmq");
    expect(calls[4][1].method).toBeUndefined();
    expect(calls[5][0]).toContain("/api/v1/config/integrations/rabbitmq");
    expect(calls[5][1].method).toBe("PUT");
    expect(calls[6][0]).toContain("/api/v1/config/review-policy");
    expect(calls[6][1].method).toBeUndefined();
    expect(calls[7][0]).toContain("/api/v1/config/review-policy");
    expect(calls[7][1].method).toBe("PUT");
    expect(calls[8][0]).toContain("/api/v1/config/review-rules");
    expect(calls[8][1].method).toBeUndefined();
    expect(calls[9][0]).toContain("/api/v1/config/review-rules");
    expect(calls[9][1].method).toBe("POST");
    expect(calls[10][0]).toContain("/api/v1/config/review-rules/RG-JAVA-001");
    expect(calls[10][1].method).toBe("PUT");
    expect(calls[11][0]).toContain("/api/v1/config/review-rules/RG-JAVA-001/status");
    expect(calls[11][1].method).toBe("PUT");
    expect(calls[11][1].body).toBe(JSON.stringify({ status: "disabled" }));
  });
});

const setCsrfCookie = (token: string) => {
  document.cookie = `repoguard_csrf_token=${encodeURIComponent(token)}; path=/`;
};

const clearCsrfCookie = () => {
  document.cookie = "repoguard_csrf_token=; Max-Age=0; path=/";
};
