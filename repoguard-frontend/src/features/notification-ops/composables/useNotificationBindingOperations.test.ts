import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, type EffectScope } from "vue";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ConnectionTestResult, NotificationBinding } from "@/types";
import { useNotificationBindings } from "./useNotificationBindings";

const api = vi.hoisted(() => ({ fetchNotificationBindings: vi.fn(), createNotificationBinding: vi.fn(), deleteNotificationBinding: vi.fn(),
  testNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(), updateNotificationBindingStatus: vi.fn() }));
const messages = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const row = (id = 1): NotificationBinding => ({ id, name: `binding-${id}`, provider: "DINGTALK", organization: "org", repository: "repo", enabled: true,
  notifyReviewCompleted: true, notifyReviewFailed: true, notifyHumanReviewRequired: true, notifyGithubComment: true, status: "CONFIGURED" });
const deferred = <T>() => { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const setup = async () => { const scope = effectScope(); scopes.push(scope); const state = scope.run(useNotificationBindings)!; await state.loadNotificationBindings(); return { state, scope }; };
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotificationBindings.mockResolvedValue({ items: [row(1), row(2)], total: 2 });
  api.updateNotificationBindingStatus.mockImplementation((id: number, payload: { enabled: boolean }) => Promise.resolve({ ...row(id), ...payload }));
  api.deleteNotificationBinding.mockResolvedValue(undefined); api.updateNotificationBinding.mockResolvedValue(row());
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("notification binding operation exclusion", () => {
  it.each(["toggle", "remove", "save", "test"])("blocks every conflicting write while %s owns the same channel", async operation => {
    const { state } = await setup(); const pending = deferred<NotificationBinding | ConnectionTestResult>();
    let call: Promise<unknown>;
    if (operation === "save") { state.openBindingDialog(row()); state.bindingForm.name = "changed"; api.updateNotificationBinding.mockReturnValueOnce(pending.promise); call = state.saveBinding(); }
    else if (operation === "remove") { api.deleteNotificationBinding.mockReturnValueOnce(pending.promise); call = state.removeBinding(1); }
    else if (operation === "test") { api.testNotificationBinding.mockReturnValueOnce(pending.promise); call = state.runBindingTest(1); }
    else { api.updateNotificationBindingStatus.mockReturnValueOnce(pending.promise); call = state.toggleBinding(row()); }
    expect(state.busyBindingIds.value).toEqual([1]); expect(state.canChangeBinding(1)).toBe(false); expect(state.canChangeBinding(2)).toBe(true);
    await state.toggleBinding(row()); await state.removeBinding(1); await state.runBindingTest(1); await state.saveBinding();
    const writes = api.updateNotificationBinding.mock.calls.length + api.updateNotificationBindingStatus.mock.calls.length
      + api.deleteNotificationBinding.mock.calls.length + api.testNotificationBinding.mock.calls.length;
    expect(writes).toBe(1); pending.resolve(operation === "test" ? { success: true, message: "connected", status: "connected", checkedAt: "2026-10-09T15:30:00Z" } : { ...row(), enabled: false }); await call; expect(state.busyBindingIds.value).toEqual([]);
  });

  it("allows independent channels to change while the first channel is pending", async () => {
    const { state } = await setup(); const first = deferred<NotificationBinding>(); const second = deferred<NotificationBinding>();
    api.updateNotificationBindingStatus.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const one = state.toggleBinding(row(1)); const two = state.toggleBinding(row(2)); expect(api.updateNotificationBindingStatus).toHaveBeenCalledTimes(2);
    expect(state.busyBindingIds.value).toEqual([1, 2]); second.resolve({ ...row(2), enabled: false }); await two;
    expect(state.busyBindingIds.value).toEqual([1]); first.resolve({ ...row(1), enabled: false }); await one; expect(state.busyBindingIds.value).toEqual([]);
  });

  it("never inserts a toggle response into a page that no longer contains the original row", async () => {
    const { state } = await setup(); const pending = deferred<NotificationBinding>(); api.updateNotificationBindingStatus.mockReturnValueOnce(pending.promise);
    const call = state.toggleBinding(row()); api.fetchNotificationBindings.mockResolvedValue({ items: [row(21)], total: 41 }); await state.changeBindingPage(2);
    pending.resolve({ ...row(), enabled: false }); await call;
    expect(state.bindingPage.value).toBe(2); expect(state.notificationBindings.value.map(item => item.id)).toEqual([21]); expect(state.bindingTotal.value).toBe(41);
  });

  it("loads authoritative rows and totals after deletion and bounds a removed last page", async () => {
    const { state } = await setup(); api.fetchNotificationBindings.mockResolvedValueOnce({ items: [row(21)], total: 21 }); await state.changeBindingPage(2);
    api.fetchNotificationBindings.mockResolvedValueOnce({ items: [], total: 20 }).mockResolvedValueOnce({ items: [row()], total: 20 }); await state.removeBinding(21);
    expect(state.bindingPage.value).toBe(1); expect(state.bindingTotal.value).toBe(20); expect(state.notificationBindings.value.map(item => item.id)).toEqual([1]);
  });

  it.each(["toggle", "remove"])("keeps the %s acknowledgement separate from refresh failure", async operation => {
    const { state } = await setup(); api.fetchNotificationBindings.mockRejectedValueOnce(new Error("refresh offline"));
    if (operation === "toggle") await state.toggleBinding(row()); else await state.removeBinding(1);
    expect(messages.success).toHaveBeenCalledTimes(1); expect(messages.error).not.toHaveBeenCalled(); expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("尚未刷新"));
    expect(state.bindingLoadError.value).toBe("refresh offline"); expect(state.canChangeBinding(1)).toBe(false); expect(state.busyBindingIds.value).toEqual([]);
  });

  it.each(["toggle", "remove"])("requires a fresh read before retrying a failed %s", async operation => {
    const { state } = await setup(); const request = operation === "toggle" ? api.updateNotificationBindingStatus : api.deleteNotificationBinding;
    request.mockRejectedValueOnce(new Error("write offline")); const run = () => operation === "toggle" ? state.toggleBinding(row()) : state.removeBinding(1);
    await run(); await run(); expect(request).toHaveBeenCalledTimes(1); expect(state.busyBindingIds.value).toEqual([]);
    await state.loadNotificationBindings(); await run(); expect(request).toHaveBeenCalledTimes(2);
  });

  it("rejects mismatched toggle acknowledgements and outdated row arguments", async () => {
    const { state } = await setup(); await state.toggleBinding({ ...row(), enabled: false }); expect(api.updateNotificationBindingStatus).not.toHaveBeenCalled();
    api.updateNotificationBindingStatus.mockResolvedValueOnce({ ...row(2), enabled: false }); await state.toggleBinding(row());
    expect(messages.success).not.toHaveBeenCalled(); expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("结果不一致"));
  });

  describe.each(["toggle", "remove"])("%s context ownership", operation => {
    it.each(["tenant", "permission", "account", "dispose"])("does not accept late results after %s changes", async reason => {
      const { state, scope } = await setup(); const pending = deferred<NotificationBinding>();
      (operation === "toggle" ? api.updateNotificationBindingStatus : api.deleteNotificationBinding).mockReturnValueOnce(pending.promise);
      const call = operation === "toggle" ? state.toggleBinding(row()) : state.removeBinding(1);
      if (reason === "tenant") setActiveTenant("other");
      if (reason === "permission") currentUser.value!.role = "VIEWER";
      if (reason === "account") currentUser.value!.id = 2;
      if (reason === "dispose") scope.stop();
      pending.resolve({ ...row(), enabled: false }); await call; expect(messages.success).not.toHaveBeenCalled(); expect(messages.error).not.toHaveBeenCalled();
      expect(api.fetchNotificationBindings).toHaveBeenCalledTimes(1); expect(state.notificationBindings.value).toEqual([]); expect(state.busyBindingIds.value).toEqual([]);
    });

    it("keeps the lock across a context round trip and invalidates the interim read when the old write settles", async () => {
      const { state } = await setup(); const pending = deferred<NotificationBinding>();
      (operation === "toggle" ? api.updateNotificationBindingStatus : api.deleteNotificationBinding).mockReturnValueOnce(pending.promise);
      const call = operation === "toggle" ? state.toggleBinding(row()) : state.removeBinding(1);
      setActiveTenant("other"); clearActiveTenant(); await state.loadNotificationBindings(); expect(state.canChangeBinding(1)).toBe(false);
      pending.resolve({ ...row(), enabled: false }); await call; expect(state.bindingsNeedRefresh.value).toBe(true); expect(state.busyBindingIds.value).toEqual([]);
      expect(messages.success).not.toHaveBeenCalled(); await state.loadNotificationBindings(); expect(state.bindingsCurrent.value).toBe(true);
    });
  });
});
