import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, type EffectScope } from "vue";
import { canManage, currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ConnectionTestResult, NotificationBinding } from "@/types";
import { useNotificationBindings } from "./useNotificationBindings";
import { useNotificationOpsTestDialog } from "./useNotificationOpsTestDialog";

const api = vi.hoisted(() => ({ fetchNotificationBindings: vi.fn(), createNotificationBinding: vi.fn(), deleteNotificationBinding: vi.fn(),
  testNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(), updateNotificationBindingStatus: vi.fn() }));
const messages = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const result = (message = "connected", success = true): ConnectionTestResult => ({ success, message, status: success ? "connected" : "failed", checkedAt: "2026-10-09T15:30:00Z" });
const row = (id = 1): NotificationBinding => ({ id, name: `binding-${id}`, provider: "DINGTALK", organization: "org", repository: "repo", enabled: true,
  notifyReviewCompleted: true, notifyReviewFailed: true, notifyHumanReviewRequired: true, notifyGithubComment: true, status: "CONFIGURED", updatedAt: "v1" });
const deferred = <T>() => { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const setup = async () => {
  const scope = effectScope(); scopes.push(scope); const state = scope.run(useNotificationBindings)!;
  const dialog = scope.run(() => useNotificationOpsTestDialog({ notificationBindings: state.notificationBindings, canManage,
    canTestBinding: state.canTestBinding, runBindingTest: state.runBindingTest }))!;
  await state.loadNotificationBindings(); dialog.openTestDialog(); return { state, dialog, scope };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotificationBindings.mockResolvedValue({ items: [row(1), row(2)], total: 2 }); api.testNotificationBinding.mockResolvedValue(result());
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("notification channel test and dialog ownership", () => {
  it("closes only after a current successful test, including its own checked-at update", async () => {
    const { state, dialog } = await setup(); api.fetchNotificationBindings.mockResolvedValueOnce({ items: [{ ...row(), updatedAt: "v2", lastCheckedAt: "checked" }, row(2)], total: 2 });
    await dialog.runSelectedBindingTest(); expect(api.testNotificationBinding).toHaveBeenCalledWith(1); expect(messages.success).toHaveBeenCalledWith("connected");
    expect(dialog.testDialogVisible.value).toBe(false); expect(dialog.sendingTest.value).toBe(false); expect(state.busyBindingIds.value).toEqual([]);
  });

  it("holds the send lock across the request and blocks a second channel test", async () => {
    const { state, dialog } = await setup(); const pending = deferred<ConnectionTestResult>(); api.testNotificationBinding.mockReturnValueOnce(pending.promise);
    const call = dialog.runSelectedBindingTest(); await dialog.runSelectedBindingTest(); expect(await state.runBindingTest(2)).toEqual({ status: "skipped" });
    expect(api.testNotificationBinding).toHaveBeenCalledTimes(1); expect(dialog.sendingTest.value).toBe(true); expect(dialog.canRunSelectedBindingTest.value).toBe(false);
    pending.resolve(result()); await call; expect(dialog.sendingTest.value).toBe(false);
  });

  it("keeps the dialog open with the actual failed connection result and allows explicit retry", async () => {
    const { dialog } = await setup(); api.testNotificationBinding.mockResolvedValueOnce(result("connection rejected", false)); await dialog.runSelectedBindingTest();
    expect(dialog.testDialogVisible.value).toBe(true); expect(dialog.testErrorMessage.value).toBe("connection rejected"); expect(messages.success).not.toHaveBeenCalled();
    await dialog.runSelectedBindingTest(); expect(api.testNotificationBinding).toHaveBeenCalledTimes(2); expect(dialog.testDialogVisible.value).toBe(false);
  });

  it("keeps a network failure visible and requires a fresh read before another send", async () => {
    const { state, dialog } = await setup(); api.testNotificationBinding.mockRejectedValueOnce(new Error("test offline")); await dialog.runSelectedBindingTest();
    expect(dialog.testDialogVisible.value).toBe(true); expect(dialog.testErrorMessage.value).toBe("test offline"); expect(dialog.canRunSelectedBindingTest.value).toBe(false);
    await dialog.runSelectedBindingTest(); expect(api.testNotificationBinding).toHaveBeenCalledTimes(1); await state.loadNotificationBindings();
    await dialog.runSelectedBindingTest(); expect(dialog.testDialogVisible.value).toBe(false);
  });

  it("retains the successful send when reading fails, keeps the dialog open and never resends automatically", async () => {
    const { state, dialog } = await setup(); api.fetchNotificationBindings.mockRejectedValueOnce(new Error("read offline")); await dialog.runSelectedBindingTest();
    expect(dialog.testDialogVisible.value).toBe(true); expect(dialog.testInfoMessage.value).toContain("发送已成功"); expect(dialog.testErrorMessage.value).toBe("");
    expect(dialog.canRunSelectedBindingTest.value).toBe(false); await state.loadNotificationBindings(); expect(api.testNotificationBinding).toHaveBeenCalledTimes(1);
  });

  it.each(["resolve", "reject"])("ignores a late %s after changing the selected channel", async outcome => {
    const { dialog } = await setup(); const pending = deferred<ConnectionTestResult>(); api.testNotificationBinding.mockReturnValueOnce(pending.promise);
    const call = dialog.runSelectedBindingTest(); dialog.selectedTestBindingId.value = 2;
    if (outcome === "resolve") pending.resolve(result("old connected")); else pending.reject(new Error("old test offline")); await call;
    expect(dialog.testDialogVisible.value).toBe(true); expect(dialog.selectedTestBindingId.value).toBe(2); expect(dialog.testErrorMessage.value).toBe("");
    expect(messages.success).not.toHaveBeenCalled(); expect(messages.error).not.toHaveBeenCalled(); expect(api.fetchNotificationBindings).toHaveBeenCalledTimes(1);
  });

  it.each(["reopen", "select-back", "config-back"])("does not close or toast into another dialog after %s", async change => {
    const { state, dialog } = await setup(); const pending = deferred<ConnectionTestResult>(); api.testNotificationBinding.mockReturnValueOnce(pending.promise); const call = dialog.runSelectedBindingTest();
    if (change === "reopen") { dialog.testDialogVisible.value = false; dialog.openTestDialog(); }
    if (change === "select-back") { dialog.selectedTestBindingId.value = 2; dialog.selectedTestBindingId.value = 1; }
    if (change === "config-back") { state.notificationBindings.value[0]!.repository = "other"; state.notificationBindings.value[0]!.repository = "repo"; }
    pending.resolve(result("old connected")); await call;
    expect(dialog.testDialogVisible.value).toBe(true); expect(messages.success).not.toHaveBeenCalled(); expect(dialog.sendingTest.value).toBe(false);
  });

  it.each(["tenant", "permission", "account", "dispose"])("clears the old dialog and ignores results after %s changes", async reason => {
    const { dialog, scope } = await setup(); const pending = deferred<ConnectionTestResult>(); api.testNotificationBinding.mockReturnValueOnce(pending.promise); const call = dialog.runSelectedBindingTest();
    if (reason === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (reason === "permission") { currentUser.value!.role = "VIEWER"; currentUser.value!.role = "ADMIN"; }
    if (reason === "account") { currentUser.value!.id = 2; currentUser.value!.id = 1; }
    if (reason === "dispose") scope.stop();
    pending.resolve(result("old connected")); await call; expect(dialog.testDialogVisible.value).toBe(false);
    expect(dialog.selectedTestBindingId.value).toBeUndefined(); expect(messages.success).not.toHaveBeenCalled(); expect(api.fetchNotificationBindings).toHaveBeenCalledTimes(1);
  });

  it("rejects a test of an older persisted version even when masked fields look identical", async () => {
    const { state, dialog } = await setup(); const pending = deferred<ConnectionTestResult>(); api.testNotificationBinding.mockReturnValueOnce(pending.promise); const call = dialog.runSelectedBindingTest();
    state.notificationBindings.value[0]!.updatedAt = "v2"; pending.resolve(result("old connected")); await call;
    expect(messages.success).not.toHaveBeenCalled(); expect(dialog.testDialogVisible.value).toBe(true); expect(dialog.testInfoMessage.value).toContain("状态已变化");
  });

  it("does not send for an unavailable, disabled, stale or unauthorized channel", async () => {
    const { state, dialog } = await setup(); dialog.selectedTestBindingId.value = 99; await dialog.runSelectedBindingTest();
    state.notificationBindings.value[0]!.enabled = false; dialog.selectedTestBindingId.value = 1; await dialog.runSelectedBindingTest();
    dialog.selectedTestBindingId.value = 2; state.bindingsNeedRefresh.value = true; await dialog.runSelectedBindingTest();
    currentUser.value!.role = "VIEWER"; dialog.openTestDialog(); await dialog.runSelectedBindingTest(); expect(api.testNotificationBinding).not.toHaveBeenCalled();
  });
});
