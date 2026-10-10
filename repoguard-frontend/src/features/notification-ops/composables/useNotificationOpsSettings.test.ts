import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { NotificationSettings, SystemSettings } from "@/types";
import { useNotificationOpsSettings } from "./useNotificationOpsSettings";

const mocks = vi.hoisted(() => ({ fetch: vi.fn(), update: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock("@/api/config", () => ({ fetchSystemSettings: mocks.fetch, updateSystemSettings: mocks.update }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: mocks }));
const scopes: EffectScope[] = [];
const settings = (notification: Partial<NotificationSettings> = {}): SystemSettings => ({
  base: { systemName: "RepoGuard", language: "zh-CN", timezone: "Asia/Shanghai", retentionDays: 30 },
  policy: { maxDiffLines: 5000, llmTimeoutSeconds: 60, workerConcurrency: 1, autoComment: false, autoRetry: false },
  security: { webhookSignature: true, secretMasking: true, publicRepoAllowed: false, tokenTtlDays: 30 },
  notification: { githubComment: true, highRiskPr: true, failedTask: true, email: "saved@example.test", ...notification }, logs: []
});
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const setup = () => {
  const scope = effectScope(); scopes.push(scope); const canManage = ref(true);
  const state = scope.run(() => useNotificationOpsSettings({ canManage }))!;
  return { ...state, scope, canManage };
};
beforeEach(() => { vi.resetAllMocks(); mocks.fetch.mockResolvedValue(settings()); mocks.update.mockImplementation(payload => Promise.resolve(settings(payload.notification))); });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); });

describe("notification settings draft preservation", () => {
  it("retains new edits on every field after save acknowledgement and allows a second explicit save", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.githubComment = false;
    const pending = deferred<SystemSettings>(); mocks.update.mockReturnValueOnce(pending.promise); const save = state.saveNotificationSettings();
    state.notificationForm.highRiskPr = false; state.notificationForm.failedTask = false; state.notificationForm.email = "new@example.test";
    await state.saveNotificationSettings(); expect(mocks.update).toHaveBeenCalledTimes(1);
    expect(mocks.update.mock.calls[0]![0].notification).toEqual(settings({ githubComment: false }).notification);
    pending.resolve(settings({ githubComment: false })); await save;
    expect(state.notificationForm).toEqual({ githubComment: false, highRiskPr: false, failedTask: false, email: "new@example.test" });
    expect(state.hasUnsavedChanges.value).toBe(true); expect(state.canSaveSettings.value).toBe(true);
    expect(mocks.success).toHaveBeenCalledWith("本次提交已保存，新的修改尚未保存");
    await state.saveNotificationSettings(); expect(mocks.update).toHaveBeenCalledTimes(2); expect(state.hasUnsavedChanges.value).toBe(false);
    expect(mocks.success).toHaveBeenLastCalledWith("通知设置已保存");
  });

  it("accepts normalization of untouched fields while retaining later edits", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.githubComment = false;
    const pending = deferred<SystemSettings>(); mocks.update.mockReturnValueOnce(pending.promise); const save = state.saveNotificationSettings();
    state.notificationForm.failedTask = false;
    pending.resolve(settings({ githubComment: false, email: "normalized@example.test" })); await save;
    expect(state.notificationForm.email).toBe("normalized@example.test"); expect(state.notificationForm.failedTask).toBe(false);
  });

  it("retains a draft after save failure and clears the error after manual retry", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.failedTask = false;
    mocks.update.mockRejectedValueOnce(new Error("save offline")); await state.saveNotificationSettings();
    expect(state.saveErrorMessage.value).toBe("save offline"); expect(state.notificationForm.failedTask).toBe(false);
    expect(state.hasUnsavedChanges.value).toBe(true); expect(state.savingSettings.value).toBe(false); expect(state.canSaveSettings.value).toBe(true);
    await state.saveNotificationSettings(); expect(state.saveErrorMessage.value).toBe(""); expect(state.hasUnsavedChanges.value).toBe(false);
  });

  it("preserves edits made before and during a refresh while refreshing untouched values", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.email = "draft@example.test";
    const pending = deferred<SystemSettings>(); mocks.fetch.mockReturnValueOnce(pending.promise); const read = state.loadSystemSettings();
    state.notificationForm.githubComment = false; pending.resolve(settings({ highRiskPr: false, email: "remote@example.test" })); await read;
    expect(state.notificationForm).toEqual({ githubComment: false, highRiskPr: false, failedTask: true, email: "draft@example.test" });
    expect(state.hasUnsavedChanges.value).toBe(true);
  });

  it("cancels an older read and does not let it clear the newer loading state", async () => {
    const state = setup(); const old = deferred<SystemSettings>(); const latest = deferred<SystemSettings>();
    mocks.fetch.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const first = state.loadSystemSettings(); const signal = mocks.fetch.mock.calls[0]![0].signal as AbortSignal;
    const second = state.loadSystemSettings(); old.resolve(settings({ email: "old@example.test" })); await first;
    expect(signal.aborted).toBe(true); expect(state.settingsLoading.value).toBe(true); expect(state.systemSettings.value).toBeUndefined();
    latest.resolve(settings()); await second; expect(state.settingsLoading.value).toBe(false); expect(state.notificationForm.email).toBe("saved@example.test");
  });

  it("ignores an old read error after a current read succeeds", async () => {
    const state = setup(); const old = deferred<SystemSettings>(); mocks.fetch.mockReturnValueOnce(old.promise);
    const first = state.loadSystemSettings(); await state.loadSystemSettings(); old.reject(new Error("old offline")); await first;
    expect(state.loadErrorMessage.value).toBe(""); expect(state.systemSettings.value).toEqual(settings());
  });

  it("blocks saving during a read and after a read failure until a successful reload", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.githubComment = false;
    const pending = deferred<SystemSettings>(); mocks.fetch.mockReturnValueOnce(pending.promise); const read = state.loadSystemSettings();
    await state.saveNotificationSettings(); expect(mocks.update).not.toHaveBeenCalled(); pending.reject(new Error("read offline")); await read;
    expect(state.loadErrorMessage.value).toBe("read offline"); await state.saveNotificationSettings(); expect(mocks.update).not.toHaveBeenCalled();
    await state.loadSystemSettings(); expect(state.notificationForm.githubComment).toBe(false); expect(state.canSaveSettings.value).toBe(true);
  });

  it("does not start a refresh during a pending write", async () => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.githubComment = false;
    const pending = deferred<SystemSettings>(); mocks.update.mockReturnValueOnce(pending.promise); const save = state.saveNotificationSettings();
    await state.loadSystemSettings(); expect(mocks.fetch).toHaveBeenCalledTimes(1);
    pending.resolve(settings({ githubComment: false })); await save; expect(state.notificationForm.githubComment).toBe(false);
  });

  it.each(["permission", "tenant", "dispose"])("ignores a pending write after %s changes and switches back", async kind => {
    const state = setup(); await state.loadSystemSettings(); state.notificationForm.githubComment = false;
    const pending = deferred<SystemSettings>(); mocks.update.mockReturnValueOnce(pending.promise); const save = state.saveNotificationSettings();
    if (kind === "permission") { state.canManage.value = false; state.canManage.value = true; }
    if (kind === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (kind === "dispose") state.scope.stop();
    pending.resolve(settings({ githubComment: false })); await save;
    expect(state.systemSettings.value).toBeUndefined(); expect(state.notificationForm.githubComment).toBe(true); expect(mocks.success).not.toHaveBeenCalled();
  });

  it("aborts the read on disposal and does not display a late error", async () => {
    const state = setup(); const pending = deferred<SystemSettings>(); mocks.fetch.mockReturnValueOnce(pending.promise);
    const read = state.loadSystemSettings(); const signal = mocks.fetch.mock.calls[0]![0].signal as AbortSignal; state.scope.stop();
    pending.reject(new Error("late offline")); await read; expect(signal.aborted).toBe(true); expect(state.loadErrorMessage.value).toBe("");
  });

  it("does not save unchanged settings or without management permission", async () => {
    const state = setup(); await state.loadSystemSettings(); await state.saveNotificationSettings();
    state.notificationForm.githubComment = false; state.canManage.value = false; await state.saveNotificationSettings(); expect(mocks.update).not.toHaveBeenCalled();
  });
});
