import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { computed, effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";
import type { ReviewTaskDetail } from "@/types";
import { useReviewDetailHumanReview } from "./useReviewDetailHumanReview";

const mocks = vi.hoisted(() => ({ prompt: vi.fn(), submit: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn() }));
vi.mock("@/api/reviews", () => ({ submitHumanReview: mocks.submit }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: mocks }));
vi.mock("element-plus/es/components/message-box/index.mjs", () => ({ ElMessageBox: { prompt: mocks.prompt } }));
const scopes: EffectScope[] = [];
const task = (id = 7) => ({ id, commit: `head-${id}`, status: "pending_human_review", archived: false,
  humanReviewRequired: true, humanReviewStatus: "pending", humanReviewNote: "previous" }) as ReviewTaskDetail;
const response = { taskId: 7, status: "approved", humanReviewStatus: "approved", humanReviewNote: "reviewed" };
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const setup = () => {
  const scope = effectScope(); scopes.push(scope); const selectedTask = ref<ReviewTaskDetail | null>(task());
  const permission = ref(true); const route = ref(7); const eligible = ref(true); const refresh = vi.fn().mockResolvedValue(undefined);
  const state = scope.run(() => useReviewDetailHumanReview({ selectedTask, getTaskId: () => route.value,
    canManage: computed(() => permission.value), canSubmitHumanReview: computed(() => eligible.value && selectedTask.value?.humanReviewStatus === "pending"),
    humanReviewActionText: action => action, refreshDetail: refresh }))!;
  return { ...state, scope, selectedTask, permission, route, eligible, refresh };
};
beforeEach(() => { vi.resetAllMocks(); mocks.prompt.mockResolvedValue({ value: " reviewed " }); mocks.submit.mockResolvedValue(response); });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("human review confirmation ownership", () => {
  it("locks the operation before opening the prompt and writes once", async () => {
    const state = setup(); const prompt = deferred<{ value: string }>(); mocks.prompt.mockReturnValueOnce(prompt.promise);
    const call = state.submitHumanReviewDecision("approve"); await state.submitHumanReviewDecision("reject");
    expect(state.submittingHumanReview.value).toBe(true); expect(mocks.prompt).toHaveBeenCalledTimes(1);
    prompt.resolve({ value: " reviewed " }); await call;
    expect(mocks.submit).toHaveBeenCalledExactlyOnceWith(7, { action: "approve", note: "reviewed" });
    expect(state.selectedTask.value?.humanReviewStatus).toBe("approved"); expect(state.submittingHumanReview.value).toBe(false);
  });

  it.each(["task", "route", "commit", "archive", "permission", "eligibility", "tenant", "account", "dispose"])("does not write after %s changes while prompting", async kind => {
    const state = setup(); const prompt = deferred<{ value: string }>(); mocks.prompt.mockReturnValueOnce(prompt.promise);
    const call = state.submitHumanReviewDecision("approve");
    if (kind === "task") { state.selectedTask.value = task(8); state.selectedTask.value = task(); }
    if (kind === "route") { state.route.value = 8; state.route.value = 7; }
    if (kind === "commit") { state.selectedTask.value!.commit = "another"; state.selectedTask.value!.commit = "head-7"; }
    if (kind === "archive") state.selectedTask.value!.archived = true;
    if (kind === "permission") { state.permission.value = false; state.permission.value = true; }
    if (kind === "eligibility") { state.eligible.value = false; state.eligible.value = true; }
    if (kind === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (kind === "account") { currentUser.value = { id: 2, username: "other", email: "other@example.test", role: "ADMIN", status: "ACTIVE" }; currentUser.value = undefined; }
    if (kind === "dispose") state.scope.stop();
    prompt.resolve({ value: "reviewed" }); await call;
    expect(mocks.submit).not.toHaveBeenCalled(); expect(mocks.success).not.toHaveBeenCalled(); expect(state.submittingHumanReview.value).toBe(false);
  });

  it.each(["resolve", "reject"])("ignores a late write %s without patching or refreshing another task", async outcome => {
    const state = setup(); const pending = deferred<typeof response>(); mocks.submit.mockReturnValueOnce(pending.promise);
    const call = state.submitHumanReviewDecision("approve"); await Promise.resolve();
    state.selectedTask.value = task(8); state.route.value = 8;
    await state.submitHumanReviewDecision("approve"); expect(mocks.submit).toHaveBeenCalledTimes(1); expect(state.submittingHumanReview.value).toBe(true);
    if (outcome === "resolve") pending.resolve(response); else pending.reject(new Error("old write failed"));
    await call; expect(state.selectedTask.value?.humanReviewStatus).toBe("pending"); expect(state.refresh).not.toHaveBeenCalled();
    expect(mocks.success).not.toHaveBeenCalled(); expect(mocks.error).not.toHaveBeenCalled(); expect(state.submittingHumanReview.value).toBe(false);
  });

  it("keeps the accepted decision when its detail refresh fails", async () => {
    const state = setup(); state.refresh.mockRejectedValueOnce(new Error("read unavailable")); await state.submitHumanReviewDecision("approve");
    expect(state.selectedTask.value?.humanReviewStatus).toBe("approved"); expect(mocks.success).toHaveBeenCalledWith("approve");
    expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining("人工审查已提交，详情刷新失败")); expect(mocks.error).not.toHaveBeenCalled();
  });

  it("rejects an acknowledgement for a different task", async () => {
    const state = setup(); mocks.submit.mockResolvedValueOnce({ ...response, taskId: 8 }); await state.submitHumanReviewDecision("approve");
    expect(state.selectedTask.value?.humanReviewStatus).toBe("pending"); expect(mocks.success).not.toHaveBeenCalled(); expect(state.refresh).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining("任务标识不一致"));
  });

  it("unlocks after cancellation or a current write failure", async () => {
    const state = setup(); mocks.prompt.mockRejectedValueOnce("cancel"); await state.submitHumanReviewDecision("approve");
    expect(mocks.submit).not.toHaveBeenCalled(); expect(mocks.error).not.toHaveBeenCalled();
    mocks.submit.mockRejectedValueOnce(new Error("submit unavailable")); await state.submitHumanReviewDecision("approve");
    expect(mocks.error).toHaveBeenCalledWith("submit unavailable"); expect(state.selectedTask.value?.humanReviewStatus).toBe("pending");
    expect(state.submittingHumanReview.value).toBe(false);
  });

  it.each(["approve", "changes_requested", "reject"] as const)("submits and applies a current %s decision", async action => {
    const state = setup(); mocks.submit.mockResolvedValueOnce({ ...response, status: action, humanReviewStatus: action });
    await state.submitHumanReviewDecision(action); expect(mocks.submit).toHaveBeenCalledWith(7, { action, note: "reviewed" });
    expect(state.selectedTask.value?.humanReviewStatus).toBe(action); expect(state.refresh).toHaveBeenCalledTimes(1);
  });

  it("does not start without permission or with a task not matching the current route", async () => {
    const state = setup(); state.permission.value = false; await state.submitHumanReviewDecision("approve");
    state.permission.value = true; state.route.value = 8; await state.submitHumanReviewDecision("approve"); expect(mocks.prompt).not.toHaveBeenCalled();
  });
});
