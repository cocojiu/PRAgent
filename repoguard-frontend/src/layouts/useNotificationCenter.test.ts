import { effectScope, type EffectScope } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearAuthToken, saveAuthToken } from "@/api/client";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { NotificationCenter, NotificationItem } from "@/types";
import { useNotificationCenter } from "./useNotificationCenter";
const api = vi.hoisted(() => ({ fetchNotifications: vi.fn(), fetchNotificationReadKeys: vi.fn(), markNotificationRead: vi.fn() }));
vi.mock("@/api/notifications", () => api);
const item = (id = "n1"): NotificationItem => ({ id, level: "warning", title: id, description: "review waiting", time: "just now", targetPath: "/repoguard/tasks" });
const center = (ids = ["n1", "n2"]): NotificationCenter => ({ total: ids.length, generatedAt: "2026-10-10T00:00:00Z", items: ids.map(id => item(id)) });
const deferred = <T>() => { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
let scopes: EffectScope[] = [];
const setup = (enabled = true) => { const scope = effectScope(); scopes.push(scope); return { scope, state: scope.run(() => useNotificationCenter({ enabled }))! }; };
const cacheKey = (id = 1, tenant = "personal") => "repoguard-read-notifications:" + JSON.stringify([id, tenant]);
beforeEach(() => {
  vi.resetAllMocks(); window.localStorage.clear(); clearActiveTenant(); saveAuthToken("test-access", false);
  currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotifications.mockResolvedValue(center()); api.fetchNotificationReadKeys.mockResolvedValue([]); api.markNotificationRead.mockResolvedValue(undefined);
});
afterEach(() => { scopes.forEach(scope => scope.stop()); scopes = []; vi.restoreAllMocks(); clearAuthToken(); currentUser.value = undefined; clearActiveTenant(); });
describe("notification account ownership and read synchronization", () => {
  it.each(["personal", "anonymous", "inactive"])("does not call enterprise endpoints for %s", async mode => {
    if (mode === "anonymous") currentUser.value = undefined;
    if (mode === "inactive") currentUser.value = { ...currentUser.value!, status: "DISABLED" };
    const { state } = setup(mode !== "personal"); await state.refreshNotifications(); await state.markNotificationRead("n1"); await state.markAllRead();
    expect(api.fetchNotifications).not.toHaveBeenCalled(); expect(api.fetchNotificationReadKeys).not.toHaveBeenCalled(); expect(api.markNotificationRead).not.toHaveBeenCalled();
  });
  it("does not migrate the old global cache into an account", async () => {
    window.localStorage.setItem("repoguard-read-notifications", JSON.stringify(["n1"])); const { state } = setup(); await state.loadNotifications();
    expect(state.isNotificationRead("n1")).toBe(false); expect(state.unreadCount.value).toBe(2);
  });
  it("loads scoped local reads as unconfirmed and isolates both account and tenant", async () => {
    window.localStorage.setItem(cacheKey(), JSON.stringify(["n1"])); const { state } = setup(); await state.loadNotifications();
    expect(state.isNotificationRead("n1")).toBe(true); expect(state.isReadConfirmed("n1")).toBe(false); expect(state.unsyncedCount.value).toBe(1);
    setActiveTenant("other"); await state.loadNotifications(); expect(state.isNotificationRead("n1")).toBe(false);
    clearActiveTenant(); await state.loadNotifications(); expect(state.isNotificationRead("n1")).toBe(true);
    currentUser.value = { ...currentUser.value!, id: 2 }; await state.loadNotifications(); expect(state.isNotificationRead("n1")).toBe(false);
  });
  it.each(['{"bad":1}', '[7]', '["valid",null]', 'broken'])("rejects malformed scoped cache %s without throwing", async raw => {
    window.localStorage.setItem(cacheKey(), raw); const { state } = setup(); expect(state.storageError.value).toContain("无法读取");
    await state.loadNotifications(); expect(state.isNotificationRead("n1")).toBe(false);
  });
  it("bounds cached entries and rejects oversized local payloads", () => {
    window.localStorage.setItem(cacheKey(), JSON.stringify(Array.from({ length: 1001 }, (_, i) => `n${i}`)));
    const { state } = setup(); expect(state.storageError.value).toContain("无法读取");
  });
  it("shows local-only read failure until a manual retry confirms the server write", async () => {
    const { state } = setup(); await state.loadNotifications(); api.markNotificationRead.mockRejectedValueOnce(new Error("server unavailable"));
    await state.markNotificationRead("n1"); expect(state.isNotificationRead("n1")).toBe(true); expect(state.isReadConfirmed("n1")).toBe(false);
    expect(state.readSyncErrors.value.n1).toBe("server unavailable"); expect(state.unsyncedCount.value).toBe(1); await flush();
    expect(api.markNotificationRead).toHaveBeenCalledOnce(); await state.markNotificationRead("n1");
    expect(state.isReadConfirmed("n1")).toBe(true); expect(state.readSyncErrors.value.n1).toBeUndefined(); expect(state.unsyncedCount.value).toBe(0);
  });
  it("deduplicates pending and already confirmed writes", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise);
    const pending = state.markNotificationRead("n1"); await state.markNotificationRead("n1"); expect(state.pendingReadIds.value.has("n1")).toBe(true);
    expect(api.markNotificationRead).toHaveBeenCalledOnce(); d.resolve(); await pending; await state.markNotificationRead("n1");
    expect(api.markNotificationRead).toHaveBeenCalledOnce(); expect(state.pendingReadIds.value.size).toBe(0);
  });
  it("does not submit keys absent from the accepted current list", async () => {
    const { state } = setup(); await state.markNotificationRead("n1"); await state.loadNotifications(); await state.markNotificationRead("unknown");
    api.fetchNotifications.mockRejectedValueOnce(new Error("read failed")); await state.loadNotifications({ force: true }); await state.markNotificationRead("n1");
    expect(state.notifications.value).toHaveLength(2); expect(state.notificationCurrent.value).toBe(false); expect(api.markNotificationRead).not.toHaveBeenCalled();
  });
  it("cancels superseded list reads and accepts only the latest response", async () => {
    const { state } = setup(); const old = deferred<NotificationCenter>(); api.fetchNotifications.mockReturnValueOnce(old.promise);
    const pending = state.loadNotifications(); const signal = api.fetchNotifications.mock.calls[0]![0].signal as AbortSignal;
    api.fetchNotifications.mockResolvedValueOnce(center(["new"])); await state.loadNotifications({ force: true }); expect(signal.aborted).toBe(true);
    old.resolve(center(["old"])); await pending; expect(state.notifications.value.map(row => row.id)).toEqual(["new"]);
  });
  it.each(["tenant", "account", "role", "dispose"])("suppresses old list and read-key results after %s", async change => {
    const keys = deferred<string[]>(); api.fetchNotificationReadKeys.mockReturnValueOnce(keys.promise); const { state, scope } = setup();
    const list = deferred<NotificationCenter>(); api.fetchNotifications.mockReturnValueOnce(list.promise); const pending = state.loadNotifications();
    const signal = api.fetchNotifications.mock.calls[0]![0].signal as AbortSignal;
    if (change === "tenant") setActiveTenant("other");
    if (change === "account") currentUser.value = { ...currentUser.value!, id: 2 };
    if (change === "role") currentUser.value = { ...currentUser.value!, role: "VIEWER" };
    if (change === "dispose") scope.stop();
    expect(signal.aborted).toBe(true); list.resolve(center(["old"])); keys.resolve(["old"]); await pending; await flush();
    expect(state.notificationCenter.value).toBeUndefined(); expect(state.isNotificationRead("old")).toBe(false);
  });
  it("ignores stale write errors after switching account", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise);
    const pending = state.markNotificationRead("n1"); currentUser.value = { ...currentUser.value!, id: 2 }; await state.loadNotifications();
    d.reject(new Error("old failure")); await pending; expect(state.readSyncErrors.value).toEqual({}); expect(state.isNotificationRead("n1")).toBe(false);
    expect(window.localStorage.getItem(cacheKey(2))).toBe("[]");
  });
  it("retains pending write ownership when returning to the original context", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise);
    const pending = state.markNotificationRead("n1"); setActiveTenant("other"); clearActiveTenant(); await state.loadNotifications();
    await state.markNotificationRead("n1"); expect(api.markNotificationRead).toHaveBeenCalledOnce(); d.resolve(); await pending;
    expect(state.notificationCurrent.value).toBe(false); expect(state.readKeysError.value).toContain("刷新");
  });
  it("preserves a successful write while a refresh is in progress", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise);
    const pending = state.markNotificationRead("n1"); const list = deferred<NotificationCenter>(); api.fetchNotifications.mockReturnValueOnce(list.promise);
    const refreshing = state.loadNotifications({ force: true }); d.resolve(); await pending; list.resolve(center()); await refreshing;
    expect(state.isReadConfirmed("n1")).toBe(true);
  });
  it("does not let an older server read erase a confirmed write", async () => {
    const keys = deferred<string[]>(); api.fetchNotificationReadKeys.mockReturnValueOnce(keys.promise); const { state } = setup(); await state.loadNotifications();
    await state.markNotificationRead("n1"); keys.resolve([]); await flush(); expect(state.isReadConfirmed("n1")).toBe(true);
  });
  it("bounds bulk writes to one at a time and reports failed synchronization without early success", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise).mockRejectedValueOnce(new Error("n2 failed"));
    const pending = state.markAllRead(); await state.markAllRead(); expect(api.markNotificationRead).toHaveBeenCalledOnce(); expect(state.bulkNotice.value).toBe("");
    expect(state.bulkSaving.value).toBe(true); d.resolve(); await pending;
    expect(state.bulkSaving.value).toBe(false); expect(state.bulkNotice.value).toContain("1 条"); expect(state.bulkNotice.value).not.toContain("已同步到");
    expect(api.markNotificationRead).toHaveBeenCalledTimes(2); await state.markAllRead(); expect(api.markNotificationRead).toHaveBeenCalledTimes(3);
    expect(state.bulkNotice.value).toBe("当前通知的已读状态已同步到服务端。");
  });
  it("stops the remaining bulk writes on a context switch", async () => {
    const { state } = setup(); await state.loadNotifications(); const d = deferred<void>(); api.markNotificationRead.mockReturnValueOnce(d.promise);
    const pending = state.markAllRead(); setActiveTenant("other"); d.resolve(); await pending; expect(api.markNotificationRead).toHaveBeenCalledOnce(); expect(state.bulkNotice.value).toBe("");
  });
  it("keeps confirmed server state when local storage cannot be written", async () => {
    const { state } = setup(); await state.loadNotifications(); vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("quota"); });
    await state.markNotificationRead("n1"); expect(state.isReadConfirmed("n1")).toBe(true); expect(state.readSyncErrors.value).toEqual({}); expect(state.storageError.value).toContain("缓存不可用");
  });
  it("shows failed server reads and retries them explicitly without replaying writes", async () => {
    api.fetchNotificationReadKeys.mockRejectedValueOnce(new Error("keys offline")); const { state } = setup(); await flush(); expect(state.readKeysError.value).toBe("keys offline");
    await state.refreshNotifications(); expect(state.readKeysError.value).toBe(""); expect(api.markNotificationRead).not.toHaveBeenCalled();
  });
  it("prunes local stale IDs when the latest list is empty", async () => {
    window.localStorage.setItem(cacheKey(), JSON.stringify(["stale"])); const { state } = setup(); api.fetchNotifications.mockResolvedValueOnce(center([]));
    await state.loadNotifications(); expect(state.isNotificationRead("stale")).toBe(false); expect(window.localStorage.getItem(cacheKey())).toBe("[]");
  });
});
