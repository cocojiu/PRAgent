import { afterEach, describe, expect, it, vi } from "vitest";
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
});
