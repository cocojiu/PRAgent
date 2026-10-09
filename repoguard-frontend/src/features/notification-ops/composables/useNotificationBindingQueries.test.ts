import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, type EffectScope } from "vue";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { NotificationBinding } from "@/types";
import { useNotificationBindings } from "./useNotificationBindings";

const api = vi.hoisted(() => ({ fetchNotificationBindings: vi.fn(), createNotificationBinding: vi.fn(), deleteNotificationBinding: vi.fn(),
  testNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(), updateNotificationBindingStatus: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() } }));
const scopes: EffectScope[] = [];
const row = (id = 1) => ({ id, name: `binding-${id}`, provider: "DINGTALK", enabled: true }) as NotificationBinding;
const page = (id = 1, total = 41) => ({ items: [row(id)], total });
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const setup = () => { const scope = effectScope(); scopes.push(scope); return { scope, state: scope.run(useNotificationBindings)! }; };
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotificationBindings.mockResolvedValue(page());
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("notification binding query ownership", () => {
  it.each(["resolve", "reject"])("ignores old %s and does not clear the newer loading state", async outcome => {
    const { state } = setup(); const old = deferred<ReturnType<typeof page>>(); const fresh = deferred<ReturnType<typeof page>>();
    api.fetchNotificationBindings.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const first = state.loadNotificationBindings(); const second = state.changeBindingPage(2);
    expect(api.fetchNotificationBindings.mock.calls[0]![1].signal.aborted).toBe(true);
    if (outcome === "resolve") old.resolve(page(1)); else old.reject(new Error("old offline")); await first;
    expect(state.bindingsLoading.value).toBe(true); expect(state.notificationBindings.value).toEqual([]); expect(state.bindingLoadError.value).toBe("");
    fresh.resolve(page(21)); await second; expect(state.notificationBindings.value[0]!.id).toBe(21);
    expect(state.bindingTotal.value).toBe(41); expect(state.bindingsLoading.value).toBe(false); expect(state.bindingsNeedRefresh.value).toBe(false);
  });

  it("retains same-query rows and total but marks failed refresh stale until a successful retry", async () => {
    const { state } = setup(); await state.loadNotificationBindings(); api.fetchNotificationBindings.mockRejectedValueOnce(new Error("current offline"));
    expect(await state.loadNotificationBindings()).toBe(false); expect(state.bindingLoadError.value).toBe("current offline");
    expect(state.notificationBindings.value[0]!.id).toBe(1); expect(state.bindingTotal.value).toBe(41); expect(state.bindingsNeedRefresh.value).toBe(true);
    expect(state.bindingsCurrent.value).toBe(false);
    await state.loadNotificationBindings(); expect(state.bindingLoadError.value).toBe(""); expect(state.bindingsNeedRefresh.value).toBe(false);
    expect(state.bindingsCurrent.value).toBe(true);
  });

  it.each(["page", "size", "tenant", "permission", "account", "dispose"])("clears old results and cancels reading on %s changes, including switch back", async reason => {
    const { state, scope } = setup(); await state.loadNotificationBindings(); const pending = deferred<ReturnType<typeof page>>();
    api.fetchNotificationBindings.mockReturnValueOnce(pending.promise); const read = state.loadNotificationBindings();
    if (reason === "page") { state.bindingPage.value = 2; state.bindingPage.value = 1; }
    if (reason === "size") { state.bindingPageSize.value = 10; state.bindingPageSize.value = 20; }
    if (reason === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (reason === "permission") { currentUser.value!.role = "VIEWER"; currentUser.value!.role = "ADMIN"; }
    if (reason === "account") { currentUser.value!.id = 2; currentUser.value!.id = 1; }
    if (reason === "dispose") scope.stop();
    expect(api.fetchNotificationBindings.mock.calls[1]![1].signal.aborted).toBe(true);
    pending.resolve(page(99)); await read; expect(state.notificationBindings.value).toEqual([]); expect(state.bindingTotal.value).toBe(0);
    expect(state.bindingsLoading.value).toBe(false); expect(state.bindingsNeedRefresh.value).toBe(true);
  });

  it("bounds a deleted last page without displaying its wrong-page results", async () => {
    const { state } = setup(); api.fetchNotificationBindings.mockResolvedValueOnce({ items: [], total: 20 }).mockResolvedValueOnce(page(1, 20));
    await state.changeBindingPage(3); expect(state.bindingPage.value).toBe(1); expect(state.bindingTotal.value).toBe(20);
    expect(api.fetchNotificationBindings).toHaveBeenNthCalledWith(2, { page: 1, pageSize: 20 }, { signal: expect.any(AbortSignal) });
    expect(state.notificationBindings.value[0]!.id).toBe(1); expect(state.bindingsLoading.value).toBe(false);
  });

  it("does not load after permission loss or scope disposal", async () => {
    const { state, scope } = setup(); currentUser.value!.role = "VIEWER"; await state.loadNotificationBindings();
    currentUser.value!.role = "ADMIN"; scope.stop(); await state.loadNotificationBindings(); expect(api.fetchNotificationBindings).not.toHaveBeenCalled();
  });
});
