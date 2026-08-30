import { expect, test, type Page, type Route } from "@playwright/test";

const csrfToken = "browser-workflow-csrf";
const alphaTaskId = 4101;
const betaTaskId = 4201;
const commitSha = "a".repeat(40);

type TenantKey = "alpha" | "beta";

type SeedState = {
  currentTenant: TenantKey;
  humanReviewPosts: number;
  manualReviewPosts: number;
  publishPosts: number;
  retryPosts: number;
  statusPolls: number;
  contractViolations: string[];
};

const apiEnvelope = (data: unknown, success = true, code = "OK", message = "OK") => ({
  success,
  code,
  message,
  data,
  timestamp: "2026-08-31T00:00:00Z"
});

const fulfillJson = (
  route: Route,
  data: unknown,
  options: { status?: number; headers?: Record<string, string>; code?: string; message?: string } = {}
) => route.fulfill({
  status: options.status ?? 200,
  contentType: "application/json",
  headers: options.headers,
  body: JSON.stringify(apiEnvelope(
    data,
    (options.status ?? 200) < 400,
    options.code,
    options.message
  ))
});

const tenantUser = (tenant: TenantKey) => ({
  id: tenant === "alpha" ? 101 : 201,
  username: `${tenant}-admin`,
  email: `${tenant}-admin@example.test`,
  role: "ADMIN",
  status: "ACTIVE"
});

const tenantTask = (tenant: TenantKey, overrides: Record<string, unknown> = {}) => {
  const alpha = tenant === "alpha";
  const status = String(overrides.status ?? "completed");
  return {
    id: alpha ? alphaTaskId : betaTaskId,
    prNumber: alpha ? 41 : 42,
    title: alpha ? "Alpha tenant review" : "Beta tenant review",
    repository: alpha ? "alpha-service" : "beta-service",
    organization: alpha ? "alpha-org" : "beta-org",
    commit: commitSha,
    branch: "feature/browser-contract",
    status,
    riskLevel: status === "failed" ? "high" : "medium",
    assessmentStatus: status === "failed" ? "failed" : "complete",
    mqRetries: 0,
    llmStatus: status === "failed" ? "failed" : "completed",
    source: "github_pr_picker",
    triggerSource: "github_pr_picker",
    createdAt: "2026-08-31 08:00:00",
    duration: "12 秒",
    humanReviewRequired: false,
    humanReviewStatus: "not_required",
    ...overrides
  };
};

const detailTask = (tenant: TenantKey, overrides: Record<string, unknown> = {}) => {
  const task = tenantTask(tenant, overrides);
  return {
    ...task,
    prUrl: `https://github.example.test/${task.organization}/${task.repository}/pull/${task.prNumber}`,
    findings: [],
    missingTests: [],
    changedFiles: [],
    findingTotal: 1,
    missingTestTotal: 0,
    changedFileTotal: 1,
    timeline: [{ label: "审查完成", time: "2026-08-31 08:00:12", status: "done" }],
    riskProfile: {
      score: 55,
      level: task.riskLevel,
      summary: "Browser seed risk profile",
      recommendHumanReview: Boolean(task.humanReviewRequired),
      humanReviewReason: "Stable browser contract",
      signals: [],
      highRiskFiles: []
    },
    prSummary: {
      overallRisk: task.riskLevel,
      summary: "Browser seed summary",
      mergeRecommendation: "Review before merge",
      recommendMerge: false,
      humanReviewRequired: Boolean(task.humanReviewRequired),
      keyRisks: [],
      focusFiles: [],
      githubCommentBody: ""
    },
    llm: {
      status: task.llmStatus,
      duration: task.duration,
      riskLevel: task.riskLevel,
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      estimatedCost: "0"
    },
    chunkedReview: {
      enabled: false,
      chunkCount: 0,
      aggregateRisk: task.riskLevel,
      aggregateFindings: 1,
      failedChunks: 0,
      reasons: []
    },
    rabbitMq: { deliveryCount: 1, retryCount: 0, consumeStatus: "ACKED" }
  };
};

const installWorkflowSeedApi = async (page: Page) => {
  const state: SeedState = {
    currentTenant: "alpha",
    humanReviewPosts: 0,
    manualReviewPosts: 0,
    publishPosts: 0,
    retryPosts: 0,
    statusPolls: 0,
    contractViolations: []
  };
  let alphaDetail = detailTask("alpha");
  let published = false;

  const requireContract = (route: Route, method: string, csrfProtected = false) => {
    const request = route.request();
    if (request.method() !== method) {
      state.contractViolations.push(`${new URL(request.url()).pathname}: expected ${method}, received ${request.method()}`);
    }
    if (!request.headers()["x-trace-id"]) {
      state.contractViolations.push(`${new URL(request.url()).pathname}: missing trace header`);
    }
    if (csrfProtected && request.headers()["x-repoguard-csrf"] !== csrfToken) {
      state.contractViolations.push(`${new URL(request.url()).pathname}: missing CSRF header`);
    }
  };

  await page.route("**/api/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === "/api/v1/auth/login") {
      const account = String(request.postDataJSON()?.account ?? "");
      state.currentTenant = account.startsWith("beta-") ? "beta" : "alpha";
      await fulfillJson(route, {
        accessToken: `test-browser-${state.currentTenant}-credential`,
        tokenType: "Bearer",
        accessTokenExpiresInSeconds: 900,
        refreshTokenExpiresInSeconds: 604800,
        user: tenantUser(state.currentTenant)
      }, {
        headers: { "set-cookie": `repoguard_csrf_token=${csrfToken}; Path=/; SameSite=Strict` }
      });
      return;
    }

    if (path === "/api/v1/auth/me") {
      requireContract(route, "GET");
      await fulfillJson(route, tenantUser(state.currentTenant));
      return;
    }

    if (path === "/api/v1/auth/logout") {
      requireContract(route, "POST", true);
      await fulfillJson(route, null, {
        headers: { "set-cookie": "repoguard_csrf_token=; Path=/; Max-Age=0; SameSite=Strict" }
      });
      return;
    }

    if (path === "/api/v1/reviews") {
      requireContract(route, "GET");
      const item = state.currentTenant === "alpha" ? alphaDetail : tenantTask("beta");
      await fulfillJson(route, { items: [item], total: 1, hasMore: false, nextCursor: null });
      return;
    }

    if (path === "/api/v1/reviews/summary") {
      requireContract(route, "GET");
      await fulfillJson(route, { total: 1, highRisk: alphaDetail.status === "failed" ? 1 : 0, failed: alphaDetail.status === "failed" ? 1 : 0, averageDurationSeconds: 12 });
      return;
    }

    if (path === "/api/v1/reviews/repositories") {
      requireContract(route, "GET");
      await fulfillJson(route, [state.currentTenant === "alpha" ? "alpha-service" : "beta-service"]);
      return;
    }

    if (path === "/api/v1/reviews/github/pull-requests") {
      requireContract(route, "GET");
      await fulfillJson(route, {
        organization: "alpha-org",
        repository: "alpha-service",
        items: [{
          number: 41,
          title: "Alpha tenant review",
          branch: "feature/browser-contract",
          headSha: commitSha,
          author: "browser-seed",
          updatedAt: "2026-08-31 08:00:00"
        }]
      });
      return;
    }

    if (path === "/api/v1/reviews/manual") {
      requireContract(route, "POST");
      state.manualReviewPosts += 1;
      const payload = request.postDataJSON();
      if (payload.commit !== commitSha || payload.prNumber !== 41 || state.currentTenant !== "alpha") {
        state.contractViolations.push("manual review payload escaped the alpha seed contract");
      }
      await fulfillJson(route, {
        taskId: alphaTaskId,
        status: "completed",
        message: state.manualReviewPosts === 1 ? "审查任务已创建" : "已复用现有审查任务",
        existing: state.manualReviewPosts > 1,
        source: "github_pr_picker",
        triggerSource: state.manualReviewPosts > 1 ? "existing_reused" : "github_pr_picker"
      });
      return;
    }

    const detailMatch = path.match(/^\/api\/v1\/reviews\/(\d+)$/);
    if (detailMatch) {
      requireContract(route, "GET");
      const taskId = Number(detailMatch[1]);
      if (state.currentTenant === "beta" && taskId === alphaTaskId) {
        await fulfillJson(route, null, {
          status: 404,
          code: "TENANT_RESOURCE_NOT_FOUND",
          message: "租户资源不存在"
        });
        return;
      }
      await fulfillJson(route, taskId === alphaTaskId ? alphaDetail : detailTask("beta"));
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/retry`) {
      requireContract(route, "POST");
      state.retryPosts += 1;
      alphaDetail = detailTask("alpha", { status: "pending", failureReason: undefined, failureSuggestion: undefined });
      await fulfillJson(route, { taskId: alphaTaskId, status: "pending", message: "任务已重新入队", retryCount: 1 });
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/status`) {
      requireContract(route, "GET");
      state.statusPolls += 1;
      if (state.statusPolls === 1) {
        await fulfillJson(route, null, {
          status: 503,
          code: "POLL_DEPENDENCY_UNAVAILABLE",
          message: "轮询依赖暂时不可用"
        });
        return;
      }
      await fulfillJson(route, {
        id: alphaTaskId,
        status: "pending",
        riskLevel: "medium",
        assessmentStatus: "complete",
        llmStatus: "completed",
        duration: "12 秒",
        humanReviewRequired: false,
        humanReviewStatus: "not_required"
      });
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/human-review`) {
      requireContract(route, "POST");
      state.humanReviewPosts += 1;
      const payload = request.postDataJSON();
      if (payload.action !== "approve") {
        state.contractViolations.push("human review action must be approve");
      }
      alphaDetail = detailTask("alpha", {
        status: "approved",
        humanReviewRequired: true,
        humanReviewStatus: "approved",
        humanReviewNote: payload.note,
        humanReviewBy: "alpha-admin",
        humanReviewedAt: "2026-08-31 08:05:00"
      });
      await fulfillJson(route, {
        taskId: alphaTaskId,
        status: "approved",
        humanReviewRequired: true,
        humanReviewStatus: "approved",
        humanReviewNote: payload.note,
        humanReviewBy: "alpha-admin",
        humanReviewedAt: "2026-08-31 08:05:00",
        message: "人工审查已通过"
      });
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/github-comments/preview`) {
      requireContract(route, "GET");
      await fulfillJson(route, {
        taskId: alphaTaskId,
        prNumber: 41,
        prUrl: "https://github.example.test/alpha-org/alpha-service/pull/41",
        writebackCheck: {
          status: "ready",
          level: "success",
          taskOwner: "alpha-org",
          taskRepository: "alpha-service",
          configuredOwner: "alpha-org",
          configuredRepository: "alpha-service",
          repositoryMatched: true,
          tokenConfigured: true,
          connectionHealthy: true,
          messages: ["GitHub 回写契约已就绪"]
        },
        totalFindings: 1,
        commentableCount: 1,
        blockedCount: 0,
        publishedCount: published ? 1 : 0,
        itemTotal: 1,
        page: 1,
        pageSize: 10,
        commentableOnly: true,
        items: [{
          findingId: 9001,
          severity: "high",
          file: "src/TenantBoundary.java",
          line: 41,
          message: "Tenant boundary must remain fail-closed",
          recommendation: "Keep tenant predicates mandatory",
          commentBody: "Keep tenant predicates mandatory.",
          commentable: true,
          targetType: "line",
          published,
          publicationStatus: published ? "published" : undefined,
          feedbackStatus: "valid"
        }]
      });
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/github-comments/publications`) {
      requireContract(route, "GET");
      await fulfillJson(route, {
        taskId: alphaTaskId,
        total: published ? 1 : 0,
        page: 1,
        pageSize: 10,
        batches: published ? [{
          batchId: 7001,
          status: "completed",
          totalFindings: 1,
          attemptedCount: 1,
          succeededCount: 1,
          failedCount: 0,
          skippedCount: 0,
          createdAt: "2026-08-31 08:06:00",
          completedAt: "2026-08-31 08:06:01",
          items: [{
            findingId: 9001,
            file: "src/TenantBoundary.java",
            line: 41,
            targetType: "line",
            success: true,
            status: "published",
            message: "评论已发布"
          }]
        }] : []
      });
      return;
    }

    if (path === `/api/v1/reviews/${alphaTaskId}/github-comments`) {
      requireContract(route, "POST");
      state.publishPosts += 1;
      published = true;
      await fulfillJson(route, {
        taskId: alphaTaskId,
        batchId: 7001,
        status: "completed",
        totalFindings: 1,
        attemptedCount: 1,
        succeededCount: 1,
        failedCount: 0,
        skippedCount: 0,
        items: [{
          findingId: 9001,
          file: "src/TenantBoundary.java",
          line: 41,
          targetType: "line",
          success: true,
          status: "published",
          message: "评论已发布"
        }]
      });
      return;
    }

    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify(apiEnvelope(null, false, "BROWSER_SEED_NOT_IMPLEMENTED", `No seed contract for ${path}`))
    });
  });

  return {
    state,
    setAlphaDetail: (overrides: Record<string, unknown>) => {
      alphaDetail = detailTask("alpha", overrides);
    }
  };
};

const login = async (page: Page, tenant: TenantKey = "alpha") => {
  await page.goto("/login");
  await page.getByPlaceholder("请输入用户名或邮箱").fill(`${tenant}-admin`);
  await page.getByPlaceholder("请输入密码").fill("Browser-Only-Password!1");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/\/repoguard\/overview$/);
};

const logout = async (page: Page) => {
  await page.locator("button.user").click();
  await page.getByRole("menuitem", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
};

test("tenant login switch keeps list data isolated and rejects a cross-tenant deep link", async ({ page }) => {
  const seed = await installWorkflowSeedApi(page);
  await login(page, "alpha");
  await page.goto("/repoguard/tasks");
  await expect(page.getByText("Alpha tenant review", { exact: true })).toBeVisible();
  await expect(page.getByText("Beta tenant review", { exact: true })).toHaveCount(0);

  await logout(page);
  await login(page, "beta");
  await page.goto("/repoguard/tasks");
  await expect(page.getByText("Beta tenant review", { exact: true })).toBeVisible();
  await expect(page.getByText("Alpha tenant review", { exact: true })).toHaveCount(0);

  await page.goto(`/repoguard/tasks/${alphaTaskId}`);
  await expect(page.getByText("租户资源不存在", { exact: true })).toBeVisible();
  await expect(page.getByText("Alpha tenant review", { exact: true })).toHaveCount(0);
  expect(seed.state.contractViolations).toEqual([]);
});

test("manual review creation reuses the same commit instead of creating a duplicate", async ({ page }) => {
  const seed = await installWorkflowSeedApi(page);
  await login(page);

  for (let attempt = 1; attempt <= 2; attempt += 1) {
    await page.goto("/repoguard/tasks");
    await page.getByRole("button", { name: "新建审查任务" }).click();
    await expect(page.getByRole("dialog", { name: "选择 GitHub PR" })).toBeVisible();
    await expect(page.getByText("#41 Alpha tenant review", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "创建审查任务" }).click();
    await expect(page).toHaveURL(new RegExp(`/repoguard/tasks/${alphaTaskId}$`));
    await expect(page.getByRole("heading", { name: /PR #41 - Alpha tenant review/ })).toBeVisible();
  }

  expect(seed.state.manualReviewPosts).toBe(2);
  expect(seed.state.contractViolations).toEqual([]);
});

test("failed review retries once and surfaces polling backoff", async ({ page }) => {
  const seed = await installWorkflowSeedApi(page);
  seed.setAlphaDetail({
    status: "failed",
    failureReason: "上游模型暂时不可用",
    failureSuggestion: "恢复依赖后重试"
  });
  await login(page);
  await page.goto(`/repoguard/tasks/${alphaTaskId}`);
  await expect(page.getByText("上游模型暂时不可用", { exact: true }).first()).toBeVisible();

  await page.getByRole("button", { name: "重试", exact: true }).last().click();
  await page.getByRole("button", { name: "确认重试" }).click();

  await expect.poll(() => seed.state.retryPosts).toBe(1);
  await expect(page.getByText("等待中", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("自动刷新失败：请求失败", { exact: true })).toBeVisible({ timeout: 7_000 });
  expect(seed.state.statusPolls).toBe(1);
  expect(seed.state.contractViolations).toEqual([]);
});

test("human approval is single-submit and unlocks GitHub preview and publication", async ({ page }) => {
  const seed = await installWorkflowSeedApi(page);
  seed.setAlphaDetail({
    status: "pending_human_review",
    humanReviewRequired: true,
    humanReviewStatus: "pending",
    humanReviewNote: "高风险发现需要人工确认"
  });
  await login(page);
  await page.goto(`/repoguard/tasks/${alphaTaskId}`);

  await page.getByRole("button", { name: "通过审查" }).click();
  await page.getByPlaceholder("可选：记录通过原因").fill("已核验租户边界");
  await page.getByRole("button", { name: "提交", exact: true }).click();
  await expect(page.getByText("人工通过", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "通过审查" })).toBeDisabled();
  expect(seed.state.humanReviewPosts).toBe(1);

  await page.getByRole("button", { name: "加载预览" }).click();
  await expect(page.getByText("Keep tenant predicates mandatory.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "回写到 GitHub" }).click();
  await page.getByRole("button", { name: "确认回写" }).click();

  await expect.poll(() => seed.state.publishPosts).toBe(1);
  await expect(page.getByText("回写历史", { exact: true })).toBeVisible();
  await expect(page.getByText("评论已发布", { exact: true }).first()).toBeVisible();
  expect(seed.state.contractViolations).toEqual([]);
});
