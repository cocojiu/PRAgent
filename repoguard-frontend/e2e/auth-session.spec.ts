import { expect, test, type Page, type Route } from "@playwright/test";

const csrfToken = "browser-e2e-csrf";

type SeedRole = "ADMIN" | "REVIEWER";

const apiEnvelope = (data: unknown) => ({
  success: true,
  code: "OK",
  message: "OK",
  data,
  timestamp: "2026-08-30T00:00:00Z"
});

const userFor = (role: SeedRole) => ({
  id: role === "ADMIN" ? 1 : 2,
  username: role === "ADMIN" ? "browser-admin" : "browser-reviewer",
  email: `${role.toLowerCase()}@example.test`,
  role,
  status: "ACTIVE"
});

const fulfillJson = (route: Route, data: unknown, headers?: Record<string, string>) =>
  route.fulfill({
    status: 200,
    contentType: "application/json",
    headers,
    body: JSON.stringify(apiEnvelope(data))
  });

const installSeedApi = async (page: Page, role: SeedRole = "ADMIN") => {
  let failNextCurrentUser = false;
  let refreshCount = 0;
  let refreshCsrfHeader = "";
  let logoutCsrfHeader = "";

  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;

    if (path === "/api/v1/auth/login") {
      await fulfillJson(route, {
        accessToken: "browser-access-token",
        tokenType: "Bearer",
        accessTokenExpiresInSeconds: 900,
        refreshTokenExpiresInSeconds: 604800,
        user: userFor(role)
      }, {
        "set-cookie": `repoguard_csrf_token=${csrfToken}; Path=/; SameSite=Strict`
      });
      return;
    }

    if (path === "/api/v1/auth/me") {
      if (failNextCurrentUser) {
        failNextCurrentUser = false;
        await route.fulfill({ status: 401, contentType: "application/json", body: "{}" });
        return;
      }
      await fulfillJson(route, userFor(role));
      return;
    }

    if (path === "/api/v1/auth/refresh") {
      refreshCount += 1;
      refreshCsrfHeader = request.headers()["x-repoguard-csrf"] ?? "";
      await fulfillJson(route, {
        accessToken: "browser-refreshed-access-token",
        tokenType: "Bearer",
        accessTokenExpiresInSeconds: 900,
        refreshTokenExpiresInSeconds: 604800,
        user: userFor(role)
      });
      return;
    }

    if (path === "/api/v1/auth/logout") {
      logoutCsrfHeader = request.headers()["x-repoguard-csrf"] ?? "";
      await fulfillJson(route, null, {
        "set-cookie": "repoguard_csrf_token=; Path=/; Max-Age=0; SameSite=Strict"
      });
      return;
    }

    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        success: false,
        code: "BROWSER_SEED_NOT_IMPLEMENTED",
        message: "The browser seed API intentionally exposes only authentication contracts.",
        timestamp: "2026-08-30T00:00:00Z"
      })
    });
  });

  return {
    expireCurrentUserOnce: () => {
      failNextCurrentUser = true;
    },
    refreshCount: () => refreshCount,
    refreshCsrfHeader: () => refreshCsrfHeader,
    logoutCsrfHeader: () => logoutCsrfHeader
  };
};

const loginFromDeepLink = async (page: Page, role: SeedRole = "ADMIN") => {
  await page.goto("/repoguard/tasks?status=FAILED");
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();

  await page.getByPlaceholder("请输入用户名或邮箱").fill(role === "ADMIN" ? "browser-admin" : "browser-reviewer");
  await page.getByPlaceholder("请输入密码").fill("Browser-Only-Password!1");
  await page.getByRole("button", { name: "登录", exact: true }).click();

  await expect(page).toHaveURL(/\/repoguard\/tasks\?status=FAILED$/);
  await expect(page.getByText("审查任务", { exact: true }).first()).toBeVisible();
};

test("real browser preserves a safe deep link and sends CSRF on cookie-backed logout", async ({ page, context }) => {
  const seed = await installSeedApi(page);
  await loginFromDeepLink(page);

  const browserStorage = await page.evaluate(() => ({
    localAccess: localStorage.getItem("repoguard.accessToken"),
    localRefresh: localStorage.getItem("repoguard.refreshToken"),
    sessionAccess: sessionStorage.getItem("repoguard.accessToken"),
    sessionRefresh: sessionStorage.getItem("repoguard.refreshToken")
  }));
  expect(browserStorage).toEqual({
    localAccess: null,
    localRefresh: null,
    sessionAccess: null,
    sessionRefresh: null
  });
  await expect.poll(async () => (await context.cookies()).find(cookie => cookie.name === "repoguard_csrf_token")?.value)
    .toBe(csrfToken);

  await page.locator("button.user").click();
  await page.getByRole("menuitem", { name: "退出登录" }).click();

  await expect(page).toHaveURL(/\/login$/);
  expect(seed.logoutCsrfHeader()).toBe(csrfToken);
});

test("real browser refreshes an HttpOnly-cookie session once after a full page reload", async ({ page }) => {
  const seed = await installSeedApi(page);
  await loginFromDeepLink(page);
  await expect(page.locator("button.user")).toContainText("browser-admin");
  await page.goto("/repoguard/users");
  await expect(page).toHaveURL(/\/repoguard\/users$/);

  seed.expireCurrentUserOnce();
  await page.reload();

  await expect.poll(seed.refreshCount).toBe(1);
  expect(seed.refreshCsrfHeader()).toBe(csrfToken);
  await expect(page).toHaveURL(/\/repoguard\/users$/);
  await expect(page.getByText("用户管理", { exact: true }).first()).toBeVisible();
});

test("real browser denies a non-management user the management deep link", async ({ page }) => {
  await installSeedApi(page, "REVIEWER");
  await loginFromDeepLink(page, "REVIEWER");
  await expect(page.locator("button.user")).toContainText("browser-reviewer");

  await page.goto("/repoguard/users");

  await expect(page).toHaveURL(/\/repoguard\/overview$/);
  await expect(page.getByText("总览", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: "用户管理" })).toHaveCount(0);
});
