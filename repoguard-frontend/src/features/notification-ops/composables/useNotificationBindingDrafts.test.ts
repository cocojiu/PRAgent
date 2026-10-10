import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, type EffectScope } from "vue";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { NotificationBinding } from "@/types";
import { useNotificationBindings } from "./useNotificationBindings";

const api = vi.hoisted(() => ({ fetchNotificationBindings: vi.fn(), createNotificationBinding: vi.fn(), deleteNotificationBinding: vi.fn(),
  testNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(), updateNotificationBindingStatus: vi.fn() }));
const messages = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const row = (id = 1): NotificationBinding => ({ id, name: `binding-${id}`, provider: "DINGTALK", organization: "org", repository: "repo", enabled: true,
  notifyReviewCompleted: true, notifyReviewFailed: true, notifyHumanReviewRequired: true, notifyGithubComment: true, status: "CONFIGURED", webhookUrl: "******" });
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const setup = async () => {
  const scope = effectScope(); scopes.push(scope); const state = scope.run(useNotificationBindings)!;
  await state.loadNotificationBindings(); state.openBindingDialog(row()); state.bindingForm.name = "submitted"; return { state, scope };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotificationBindings.mockResolvedValue({ items: [row(1), row(2)], total: 41 });
  api.updateNotificationBinding.mockResolvedValue({ ...row(), name: "submitted" }); api.createNotificationBinding.mockResolvedValue(row(42));
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("notification binding draft ownership", () => {
  it("preserves edits made during save, accepts normalized fields and lets the same editor save the later draft", async () => {
    const { state } = await setup(); const pending = deferred<NotificationBinding>(); api.updateNotificationBinding.mockReturnValueOnce(pending.promise);
    const save = state.saveBinding(); state.bindingForm.name = "later"; state.bindingForm.enabled = false; await state.saveBinding();
    expect(api.updateNotificationBinding).toHaveBeenCalledTimes(1); expect(api.updateNotificationBinding.mock.calls[0]![1].name).toBe("submitted");
    pending.resolve({ ...row(), name: "normalized", provider: "WECOM" }); await save;
    expect(state.bindingForm.name).toBe("later"); expect(state.bindingForm.enabled).toBe(false); expect(state.bindingForm.provider).toBe("WECOM");
    expect(state.bindingDialogVisible.value).toBe(true); expect(state.bindingHasUnsavedChanges.value).toBe(true); expect(state.bindingCanSave.value).toBe(true);
    api.updateNotificationBinding.mockResolvedValueOnce({ ...row(), name: "later", enabled: false, provider: "WECOM" }); await state.saveBinding();
    expect(state.bindingDialogVisible.value).toBe(false); expect(state.bindingHasUnsavedChanges.value).toBe(false);
  });

  it("turns a newly created dirty draft into an edit so a second save cannot create a duplicate", async () => {
    const { state } = await setup(); state.openBindingDialog(); state.bindingForm.name = "new";
    const pending = deferred<NotificationBinding>(); api.createNotificationBinding.mockReturnValueOnce(pending.promise); const save = state.saveBinding();
    state.bindingForm.name = "new later"; pending.resolve({ ...row(42), name: "new" }); await save;
    expect(state.editingBindingId.value).toBe(42); expect(state.bindingDialogVisible.value).toBe(true);
    api.updateNotificationBinding.mockResolvedValueOnce({ ...row(42), name: "new later" });
    await state.saveBinding(); expect(api.createNotificationBinding).toHaveBeenCalledTimes(1); expect(state.bindingDialogVisible.value).toBe(false);
    expect(api.updateNotificationBinding).toHaveBeenCalledWith(42, expect.objectContaining({ name: "new later" }));
  });

  it("keeps edits made during the post-save refresh and closes only an unchanged original editor", async () => {
    const { state } = await setup(); const read = deferred<{ items: NotificationBinding[]; total: number }>(); api.fetchNotificationBindings.mockReturnValueOnce(read.promise);
    const save = state.saveBinding(); await Promise.resolve(); await Promise.resolve(); state.bindingForm.repository = "later-repo";
    read.resolve({ items: [row()], total: 1 }); await save; expect(state.bindingDialogVisible.value).toBe(true); expect(state.bindingHasUnsavedChanges.value).toBe(true);
  });

  it.each(["resolve", "reject"])("ignores late %s after another editor opens and never closes it", async outcome => {
    const { state } = await setup(); const pending = deferred<NotificationBinding>(); api.createNotificationBinding.mockReturnValueOnce(pending.promise);
    state.openBindingDialog(); const save = state.saveBinding(); state.bindingDialogVisible.value = false;
    // A newly opened create editor is independent of the previous in-flight create.
    state.openBindingDialog(); state.bindingForm.name = "another editor"; const pageBefore = state.bindingPage.value;
    if (outcome === "resolve") pending.resolve(row(42)); else pending.reject(new Error("old failure")); await save;
    expect(state.bindingDialogVisible.value).toBe(true); expect(state.editingBindingId.value).toBeUndefined(); expect(state.bindingForm.name).toBe("another editor");
    expect(state.bindingPage.value).toBe(pageBefore); expect(messages.success).not.toHaveBeenCalled(); expect(messages.error).not.toHaveBeenCalled();
    expect(api.fetchNotificationBindings).toHaveBeenCalledTimes(1); expect(state.bindingsNeedRefresh.value).toBe(true);
  });

  it.each(["tenant", "permission", "account", "dispose"])("ignores saved data after %s changes, including switch back", async reason => {
    const { state, scope } = await setup(); const pending = deferred<NotificationBinding>(); api.updateNotificationBinding.mockReturnValueOnce(pending.promise); const save = state.saveBinding();
    if (reason === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (reason === "permission") { currentUser.value!.role = "VIEWER"; currentUser.value!.role = "ADMIN"; }
    if (reason === "account") { currentUser.value!.id = 2; currentUser.value!.id = 1; }
    if (reason === "dispose") scope.stop();
    pending.resolve(row()); await save; expect(state.bindingDialogVisible.value).toBe(false); expect(state.editingBindingId.value).toBeUndefined();
    expect(state.bindingForm.name).toBe(""); expect(state.bindingForm.webhookUrl).toBe(""); expect(messages.success).not.toHaveBeenCalled(); expect(state.savingBinding.value).toBe(false);
  });

  it("keeps the draft after a write failure and allows explicit retry", async () => {
    const { state } = await setup(); api.updateNotificationBinding.mockRejectedValueOnce(new Error("write offline")); await state.saveBinding();
    expect(state.bindingSaveError.value).toBe("write offline"); expect(state.bindingForm.name).toBe("submitted"); expect(state.bindingDialogVisible.value).toBe(true);
    await state.saveBinding(); expect(api.updateNotificationBinding).toHaveBeenCalledTimes(2); expect(state.bindingSaveError.value).toBe("");
  });

  it("retains the save acknowledgement when refresh fails without claiming the write failed", async () => {
    const { state } = await setup(); api.fetchNotificationBindings.mockRejectedValueOnce(new Error("refresh offline")); await state.saveBinding();
    expect(messages.success).toHaveBeenCalledWith("消息通知绑定已保存"); expect(messages.error).not.toHaveBeenCalled(); expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("已保存"));
    expect(state.bindingLoadError.value).toBe("refresh offline"); expect(state.bindingsCurrent.value).toBe(false);
  });

  it("rejects a mismatched saved channel and preserves the draft", async () => {
    const { state } = await setup(); api.updateNotificationBinding.mockResolvedValueOnce(row(2)); await state.saveBinding();
    expect(state.bindingForm.name).toBe("submitted"); expect(state.bindingDialogVisible.value).toBe(true); expect(messages.success).not.toHaveBeenCalled();
    expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("标识不一致"));
  });

  it("cancels an older read before writing so it cannot change accepted draft data", async () => {
    const { state } = await setup(); const pending = deferred<{ items: NotificationBinding[]; total: number }>(); api.fetchNotificationBindings.mockReturnValueOnce(pending.promise);
    const read = state.loadNotificationBindings(); await state.saveBinding(); expect(api.fetchNotificationBindings.mock.calls[1]![1].signal.aborted).toBe(true);
    pending.resolve({ items: [{ ...row(), name: "old" }], total: 999 }); await read; expect(state.bindingForm.name).toBe("submitted"); expect(state.bindingTotal.value).toBe(41);
  });

  it("does not submit an unchanged, hidden or unauthorized editor", async () => {
    const { state } = await setup(); state.bindingForm.name = "binding-1"; await state.saveBinding();
    state.bindingForm.name = "changed"; state.bindingDialogVisible.value = false; await state.saveBinding();
    currentUser.value!.role = "VIEWER"; await state.saveBinding(); expect(api.updateNotificationBinding).not.toHaveBeenCalled();
  });
});
