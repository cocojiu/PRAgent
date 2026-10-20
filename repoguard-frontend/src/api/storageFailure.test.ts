import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { browserStorage } from "@/utils/browserStorage";

beforeEach(() => { vi.resetModules(); });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); window.localStorage.clear(); window.sessionStorage.clear(); });
const deny = (kind: "localStorage" | "sessionStorage") => vi.spyOn(window, kind, "get")
  .mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });

describe("storage adapter and session fault isolation", () => {
  it.each(["local", "session"] as const)("handles a denied %s property getter", kind => {
    deny(kind === "local" ? "localStorage" : "sessionStorage");
    expect(browserStorage.get(kind, "key")).toEqual({ available: false });
    expect(browserStorage.set(kind, "key", "value")).toEqual({ available: false });
    expect(browserStorage.remove(kind, "key")).toEqual({ available: false });
  });
  it.each(["getItem", "setItem", "removeItem"] as const)("handles a failing %s method", method => {
    vi.spyOn(Storage.prototype, method).mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });
    expect(({ getItem: () => browserStorage.get("local", "key"),
      setItem: () => browserStorage.set("local", "key", "value"),
      removeItem: () => browserStorage.remove("local", "key") })[method]()).toEqual({ available: false });
  });
  it("distinguishes missing values from unavailable storage and keeps stores independent", () => {
    expect(browserStorage.get("local", "missing")).toEqual({ available: true, value: null });
    browserStorage.set("local", "key", "local"); browserStorage.set("session", "key", "session");
    expect(browserStorage.get("local", "key")).toEqual({ available: true, value: "local" });
    expect(browserStorage.get("session", "key")).toEqual({ available: true, value: "session" });
  });
  it("is unavailable outside a browser", () => {
    vi.stubGlobal("window", undefined);
    expect(browserStorage.get("local", "key")).toEqual({ available: false });
  });
  it.each(["localStorage", "sessionStorage", "both"] as const)("keeps login, refresh and logout usable with %s denied", async kind => {
    if (kind !== "sessionStorage") deny("localStorage");
    if (kind !== "localStorage") deny("sessionStorage");
    const auth = await import("./authSession");
    expect(auth.hasAuthToken()).toBe(false);
    expect(auth.hasSessionMarker()).toBe(false);
    auth.saveAuthTokens("access", "unused-refresh", true);
    expect(auth.resolveAccessToken()).toBe("access");
    expect(auth.resolveRefreshToken()).toBe("");
    expect(auth.hasSessionMarker()).toBe(true);
    expect(auth.isSessionRemembered()).toBe(true);
    auth.saveRefreshedAuthToken("renewed", true);
    expect(auth.resolveAccessToken()).toBe("renewed");
    const revision = auth.resolveAuthSessionRevision();
    auth.clearAuthToken();
    expect(auth.resolveAuthSessionRevision()).toBe(revision + 1);
    expect(auth.hasAuthToken()).toBe(false);
    expect(auth.isSessionRemembered()).toBe(false);
  });
  it("survives quota exhaustion without persisting credentials", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("Full", "QuotaExceededError"); });
    const auth = await import("./authSession");
    auth.saveAuthTokens("access", "unused-refresh", false);
    expect(auth.resolveAccessToken()).toBe("access");
    expect(auth.hasSessionMarker()).toBe(true);
    expect(auth.isSessionRemembered()).toBe(false);
    expect(window.localStorage.length).toBe(0); expect(window.sessionStorage.length).toBe(0);
  });
  it("cannot resurrect stale legacy tokens or markers after logout removal fails", async () => {
    window.localStorage.setItem("repoguard.accessToken", "stale");
    window.localStorage.setItem("repoguard.session", "active");
    const auth = await import("./authSession");
    const removal = vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => { throw new Error("Denied"); });
    auth.clearAuthToken(); removal.mockRestore();
    expect(auth.resolveAccessToken()).toBe(""); expect(auth.hasAuthToken()).toBe(false);
    expect(auth.hasSessionMarker()).toBe(false);
  });
  it("imports a legacy access token once and clears it from both stores", async () => {
    window.localStorage.setItem("repoguard.authToken", "legacy");
    const auth = await import("./authSession");
    expect(auth.resolveAccessToken()).toBe("legacy");
    expect(window.localStorage.getItem("repoguard.authToken")).toBeNull();
    auth.clearAuthToken(); expect(auth.resolveAccessToken()).toBe("");
  });
  it("continues observing marker removal from another tab when storage works", async () => {
    const auth = await import("./authSession"); auth.saveAuthTokens("access", "", true);
    expect(auth.hasSessionMarker()).toBe(true);
    window.localStorage.removeItem("repoguard.session");
    expect(auth.hasSessionMarker()).toBe(false);
    expect(auth.isSessionRemembered()).toBe(false);
  });
  it("keeps an existing session usable when storage becomes unavailable mid-page", async () => {
    const auth = await import("./authSession"); auth.saveAuthTokens("access", "", true);
    expect(auth.hasSessionMarker()).toBe(true); deny("localStorage"); deny("sessionStorage");
    expect(auth.hasSessionMarker()).toBe(true); expect(auth.isSessionRemembered()).toBe(true);
    auth.clearAuthToken(); expect(auth.hasSessionMarker()).toBe(false);
  });
  it("keeps the current remember choice when an older persistent marker cannot be removed", async () => {
    window.localStorage.setItem("repoguard.session", "active");
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => { throw new Error("Denied"); });
    const auth = await import("./authSession"); auth.saveAuthTokens("new-access", "", false);
    expect(auth.isSessionRemembered()).toBe(false); expect(auth.hasSessionMarker()).toBe(true);
    expect(auth.resolveAccessToken()).toBe("new-access");
    auth.clearAuthToken(); expect(auth.hasSessionMarker()).toBe(false);
  });
  it("restores only a marker on a new page, never an in-memory credential", async () => {
    const auth = await import("./authSession"); auth.saveAuthTokens("access", "unused-refresh", true);
    vi.resetModules(); const reloaded = await import("./authSession");
    expect(reloaded.hasSessionMarker()).toBe(true); expect(reloaded.resolveAccessToken()).toBe("");
    expect(reloaded.resolveRefreshToken()).toBe("");
    expect(window.localStorage.getItem("repoguard.accessToken")).toBeNull();
  });
  it("rejects a late refresh after logout even with storage denied", async () => {
    deny("localStorage"); deny("sessionStorage");
    const auth = await import("./authSession");
    const { AuthSessionRefreshCoordinator } = await import("./authRefreshCoordinator");
    auth.saveAuthTokens("expired", "", true);
    const response = { ok: true, body: { success: true, code: "OK", message: "ok", data: { accessToken: "late" }, timestamp: "now" } };
    let complete!: (value: typeof response) => void;
    const pending = new Promise<typeof response>(resolve => { complete = resolve; });
    const coordinator = new AuthSessionRefreshCoordinator(() => pending, { channel: null, runWithCrossTabLock: null });
    const result = coordinator.refreshSessionResult(); auth.clearAuthToken(); complete(response);
    expect((await result).kind).toBe("rejected"); expect(auth.hasAuthToken()).toBe(false);
  });
  it("imports tenant context under denied storage without selecting a default tenant", async () => {
    deny("localStorage"); const tenant = await import("@/stores/tenantContext");
    expect(tenant.activeTenant.value).toBe(""); tenant.setActiveTenant("Acme"); tenant.resetActiveTenantFromStorage();
    expect(tenant.activeTenant.value).toBe("acme"); tenant.clearActiveTenant();
    expect(tenant.activeTenant.value).toBe("");
  });
  it("does not restore an older tenant after a failed write and retries persistence on recovery", async () => {
    window.localStorage.setItem("repoguard-active-tenant", "old");
    const tenant = await import("@/stores/tenantContext");
    const denied = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("Full"); });
    tenant.setActiveTenant("NEW"); tenant.resetActiveTenantFromStorage(); expect(tenant.activeTenant.value).toBe("new");
    denied.mockRestore(); tenant.resetActiveTenantFromStorage();
    expect(window.localStorage.getItem("repoguard-active-tenant")).toBe("new");
  });
  it("keeps a failed tenant removal cleared when storage recovers", async () => {
    const tenant = await import("@/stores/tenantContext"); tenant.setActiveTenant("acme");
    const denied = vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => { throw new Error("Denied"); });
    tenant.clearActiveTenant(); expect(tenant.activeTenant.value).toBe("");
    denied.mockRestore(); tenant.resetActiveTenantFromStorage(); expect(tenant.activeTenant.value).toBe("");
    expect(window.localStorage.getItem("repoguard-active-tenant")).toBeNull();
  });
});
