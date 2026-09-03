import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearAuthToken, hasAuthToken, saveAuthToken } from "@/api/client";
import { resetCurrentUser } from "@/stores/authState";
import { routeNames } from "@/router/names";
import { router } from "@/router";

const messages = vi.hoisted(() => ({
  error: vi.fn()
}));

vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: messages
}));

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

const errorResponse = (status: number) => new Response("error", { status });

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
    vi.clearAllMocks();
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
  }, 10_000);

  it("restores only safe internal redirects for authenticated users", async () => {
    saveAuthToken("access-token", false);

    const internal = await navigate("/login?redirect=%2Frepoguard%2Ftasks%3Fpage%3D2");
    expect(internal.fullPath).toBe("/repoguard/tasks?page=2");

    const external = await navigate("/login?redirect=https%3A%2F%2Fevil.example%2Fsteal");
    expect(external.fullPath).toBe("/repoguard/overview");
  });

  it("keeps enterprise operations out of the personal route surface", async () => {
    saveAuthToken("access-token", false);

    expect((await navigate("/repoguard/message-queue")).name).toBe(routeNames.overview);
    expect((await navigate("/repoguard/notifications")).name).toBe(routeNames.overview);
    expect((await navigate("/repoguard/users")).name).toBe(routeNames.overview);
    expect((await navigate("/repoguard/tenants")).name).toBe(routeNames.overview);
    await expectResolvedRoute("/repoguard/integrations", routeNames.integrations);
    await expectResolvedRoute("/repoguard/settings", routeNames.settings);
  });

  it("keeps advanced pages behind lazy route boundaries", async () => {
    for (const routeName of [routeNames.messageQueue, routeNames.notificationOps, routeNames.users, routeNames.tenants]) {
      const route = router.getRoutes().find((candidate) => candidate.name === routeName);
      const loader = route?.components?.default as (() => Promise<unknown>) | undefined;
      expect(loader).toBeTypeOf("function");
      await loader?.();
    }
  });

  it("redirects to overview with a message when loading the current user fails without 401", async () => {
    saveAuthToken("access-token", false);
    vi.stubGlobal("fetch", vi.fn(async () => errorResponse(502)));

    const route = await navigate("/repoguard/settings");

    expect(route.name).toBe(routeNames.overview);
    expect(messages.error).toHaveBeenCalledWith("权限信息加载失败，请稍后重试");
  });

  it("clears the local session and redirects to login after a management 401", async () => {
    saveAuthToken("access-token", false);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes("/api/v1/auth/refresh")) {
        return okResponse({ accessToken: "new-access" });
      }
      return errorResponse(401);
    }));

    const route = await navigate("/repoguard/settings");

    expect(route.name).toBe(routeNames.login);
    expect(route.query.redirect).toBe("/repoguard/settings");
    expect(hasAuthToken()).toBe(false);
    expect(messages.error).not.toHaveBeenCalled();
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
