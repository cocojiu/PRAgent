import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearAuthToken, saveAuthToken } from "@/api/client";
import { resetCurrentUser } from "@/stores/authState";
import { routeNames } from "@/router/names";
import { router } from "@/router";

const okResponse = (data: unknown) => new Response(JSON.stringify({
  success: true,
  code: "OK",
  message: "OK",
  data,
  timestamp: "2026-07-07T00:00:00"
}), {
  status: 200,
  headers: { "Content-Type": "application/json" }
});

const currentAdminUser = {
  id: 1,
  username: "admin",
  email: "admin@example.com",
  role: "ADMIN",
  status: "ACTIVE"
};

describe("application route smoke", () => {
  beforeEach(async () => {
    window.history.pushState({}, "", "/");
    clearAuthToken();
    resetCurrentUser();
    vi.stubGlobal("fetch", vi.fn(async () => okResponse(currentAdminUser)));
    await navigate("/login");
  });

  afterEach(() => {
    clearAuthToken();
    resetCurrentUser();
    vi.unstubAllGlobals();
  });

  it("redirects protected pages to login when no session is available", async () => {
    const route = await navigate("/repoguard/tasks");

    expect(route.name).toBe(routeNames.login);
    expect(route.query.redirect).toBe("/repoguard/tasks");
  });

  it("loads core authenticated routes", async () => {
    saveAuthToken("access-token", false);

    await expectResolvedRoute("/repoguard/overview", routeNames.overview);
    await expectResolvedRoute("/repoguard/tasks", routeNames.tasks);
    await expectResolvedRoute("/repoguard/tasks/42", routeNames.taskDetail);
  });

  it("loads management routes after resolving the current admin user", async () => {
    saveAuthToken("access-token", false);

    await expectResolvedRoute("/repoguard/message-queue", routeNames.messageQueue);
    await expectResolvedRoute("/repoguard/notifications", routeNames.notificationOps);
    await expectResolvedRoute("/repoguard/integrations", routeNames.integrations);
    await expectResolvedRoute("/repoguard/settings", routeNames.settings);

    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/auth/me"),
      expect.objectContaining({ credentials: "include" })
    );
  });
});

const navigate = async (path: string) => {
  await router.push(path);
  await router.isReady();
  return router.currentRoute.value;
};

const expectResolvedRoute = async (path: string, name: string) => {
  const route = await navigate(path);
  expect(route.name).toBe(name);
  expect(route.matched.at(-1)?.components?.default).toBeTruthy();
};
