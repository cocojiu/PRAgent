import { afterEach, describe, expect, it, vi } from "vitest";
import { RequestError } from "@/utils/errors";
import { clearAuthToken, hasAuthToken, request, resolveRefreshToken, saveAuthTokens } from "./client";

const apiResponse = (data: unknown, status = 200) =>
  new Response(JSON.stringify({
    success: status >= 200 && status < 300,
    code: status >= 200 && status < 300 ? "OK" : "UNAUTHORIZED",
    message: status >= 200 && status < 300 ? "success" : "unauthorized",
    data,
    timestamp: "2026-07-03T21:45:00+08:00"
  }), {
    status,
    headers: { "Content-Type": "application/json" }
  });

describe("auth token client", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    clearAuthToken();
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  it("does not persist refresh tokens in web storage", () => {
    saveAuthTokens("access-token", "refresh-token", true);

    expect(resolveRefreshToken()).toBe("");
    expect(window.sessionStorage.getItem("repoguard.refreshToken")).toBeNull();
    expect(window.localStorage.getItem("repoguard.refreshToken")).toBeNull();
    expect(window.localStorage.getItem("repoguard.session")).toBe("active");
    expect(hasAuthToken()).toBe(true);
  });

  it("refreshes a request by using the HttpOnly refresh cookie session marker", async () => {
    saveAuthTokens("expired-access", "refresh-token", true);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(apiResponse(null, 401))
      .mockResolvedValueOnce(apiResponse({ accessToken: "new-access" }))
      .mockResolvedValueOnce(apiResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(request<{ ok: boolean }>("/api/v1/protected")).resolves.toEqual({ ok: true });

    const [, refreshInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(refreshInit.credentials).toBe("include");
    expect(refreshInit.body).toBeUndefined();
    expect(new Headers(refreshInit.headers).has("Content-Type")).toBe(false);

    const [, retryInit] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect(new Headers(retryInit.headers).get("Authorization")).toBe("Bearer new-access");
  });

  it("shares one refresh request across concurrent unauthorized responses", async () => {
    saveAuthTokens("expired-access", "refresh-token", true);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(apiResponse(null, 401))
      .mockResolvedValueOnce(apiResponse(null, 401))
      .mockResolvedValueOnce(apiResponse({ accessToken: "shared-access" }))
      .mockResolvedValueOnce(apiResponse({ first: true }))
      .mockResolvedValueOnce(apiResponse({ second: true }));
    vi.stubGlobal("fetch", fetchMock);

    const [first, second] = await Promise.all([
      request<{ first: boolean }>("/api/v1/protected/first"),
      request<{ second: boolean }>("/api/v1/protected/second")
    ]);

    expect(first).toEqual({ first: true });
    expect(second).toEqual({ second: true });
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes("/api/v1/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
    const retryAuthorizations = fetchMock.mock.calls.slice(3).map(([, init]) =>
      new Headers((init as RequestInit).headers).get("Authorization")
    );
    expect(retryAuthorizations).toEqual(["Bearer shared-access", "Bearer shared-access"]);
  });

  it("normalizes failed api envelopes into request errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(apiResponse(null, 403)));

    await expect(request("/api/v1/admin-only")).rejects.toMatchObject({
      name: "RequestError",
      status: 403,
      code: "UNAUTHORIZED",
      message: "unauthorized"
    });
  });

  it("normalizes network failures into request errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")));

    await expect(request("/api/v1/protected")).rejects.toMatchObject({
      name: "RequestError",
      status: 0,
      code: "NETWORK_ERROR"
    });
  });

  it("does not wrap existing request errors", async () => {
    const original = new RequestError("custom", { status: 418, code: "TEAPOT" });
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(original));

    await expect(request("/api/v1/protected")).rejects.toBe(original);
  });
});
