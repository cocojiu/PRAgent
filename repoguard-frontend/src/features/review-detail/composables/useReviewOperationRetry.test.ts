import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { computed, effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";
import type { ReviewTask, ReviewTaskDetail } from "@/types";
import { useReviewDetailRetry } from "./useReviewDetailRetry";
import { useReviewTaskRetry } from "@/features/review-tasks/composables/useReviewTaskRetry";

const mocks = vi.hoisted(() => ({ confirm: vi.fn(), retry: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn() }));
vi.mock("@/api/reviews", () => ({ retryReview: mocks.retry }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: mocks }));
vi.mock("element-plus/es/components/message-box/index.mjs", () => ({ ElMessageBox: { confirm: mocks.confirm } }));
const scopes: EffectScope[] = [];
const task = () => ({ id: 7, commit: "head-7", status: "failed", prNumber: 19, archived: false }) as ReviewTaskDetail;
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const response = { taskId: 7, status: "queued", message: "已重新入队" };
const setup = (kind: "detail" | "list") => {
  const scope = effectScope(); scopes.push(scope);
  const selected = ref<ReviewTaskDetail | null>(task()); const rows = ref<ReviewTask[]>([task() as ReviewTask]);
  const route = ref(7); const filter = ref("all"); const allowed = ref(true);
  const refresh = vi.fn().mockResolvedValue(undefined); const clear = vi.fn(); const reset = vi.fn();
  const state = scope.run(() => {
    if (kind === "detail") {
      const operation = useReviewDetailRetry({ canManage: computed(() => allowed.value),
        canRetryTask: computed(() => ["failed", "superseded"].includes(selected.value?.status ?? "")),
        selectedTask: selected, getTaskId: () => route.value, failureReason: computed(() => "reason"),
        refreshDetail: refresh, clearGithubCommentState: clear, resetPollFailure: reset });
      return { run: () => operation.confirmRetryReview(), busy: () => operation.retryingTask.value };
    }
    const operation = useReviewTaskRetry({ canManage: allowed, getTask: id => rows.value.find(row => row.id === id),
      getContextKey: () => filter.value, onRetried: refresh });
    return { run: () => operation.retryTask(rows.value[0]!), busy: () => operation.retryingTaskId.value !== undefined };
  })!;
  return { ...state, scope, selected, rows, route, filter, allowed, refresh, clear, reset,
    activeTask: () => kind === "detail" ? selected.value! : rows.value[0]! };
};
beforeEach(() => { vi.resetAllMocks(); mocks.confirm.mockResolvedValue("confirm"); mocks.retry.mockResolvedValue(response); });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe.each(["detail", "list"] as const)("%s review retry ownership", kind => {
  it("holds the lock throughout confirmation, write and refresh", async () => {
    const state = setup(kind); const confirm = deferred<void>(); const read = deferred<void>();
    mocks.confirm.mockReturnValueOnce(confirm.promise); state.refresh.mockReturnValueOnce(read.promise);
    const call = state.run(); await state.run(); expect(mocks.confirm).toHaveBeenCalledTimes(1); expect(state.busy()).toBe(true);
    confirm.resolve(); await Promise.resolve(); await Promise.resolve(); await state.run(); expect(mocks.retry).toHaveBeenCalledTimes(1);
    expect(state.busy()).toBe(true); read.resolve(); await call; expect(state.busy()).toBe(false);
  });

  it.each(["view", "commit", "status", "permission", "tenant", "account", "dispose"])("does not write after %s changes during confirmation, even after switching back", async reason => {
    const state = setup(kind); const confirm = deferred<void>(); mocks.confirm.mockReturnValueOnce(confirm.promise); const call = state.run();
    if (reason === "view") { if (kind === "detail") { state.route.value = 8; state.route.value = 7; } else { state.filter.value = "other"; state.filter.value = "all"; } }
    if (reason === "commit") { state.activeTask().commit = "new"; state.activeTask().commit = "head-7"; }
    if (reason === "status") { state.activeTask().status = "pending"; state.activeTask().status = "failed"; }
    if (reason === "permission") { state.allowed.value = false; state.allowed.value = true; }
    if (reason === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (reason === "account") { currentUser.value = { id: 2, username: "other", email: "other@example.test", role: "ADMIN", status: "ACTIVE" }; currentUser.value = undefined; }
    if (reason === "dispose") state.scope.stop();
    confirm.resolve(); await call; expect(mocks.retry).not.toHaveBeenCalled(); expect(mocks.success).not.toHaveBeenCalled(); expect(state.busy()).toBe(false);
  });

  it.each(["resolve", "reject"])("ignores a late %s after leaving the original view", async outcome => {
    const state = setup(kind); const pending = deferred<typeof response>(); mocks.retry.mockReturnValueOnce(pending.promise);
    const call = state.run(); await Promise.resolve();
    if (kind === "detail") state.route.value = 8; else state.filter.value = "other";
    await state.run(); expect(mocks.retry).toHaveBeenCalledTimes(1);
    if (outcome === "resolve") pending.resolve(response); else pending.reject(new Error("old failure")); await call;
    expect(mocks.success).not.toHaveBeenCalled(); expect(mocks.error).not.toHaveBeenCalled(); expect(state.refresh).not.toHaveBeenCalled();
    expect(state.clear).not.toHaveBeenCalled(); expect(state.reset).not.toHaveBeenCalled(); expect(state.busy()).toBe(false);
  });

  it("retains the enqueue acknowledgement when refresh fails", async () => {
    const state = setup(kind); state.refresh.mockRejectedValueOnce(new Error("refresh unavailable")); await state.run();
    expect(mocks.success).toHaveBeenCalledWith("已重新入队"); expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining("刷新失败"));
    expect(mocks.error).not.toHaveBeenCalled(); expect(mocks.retry).toHaveBeenCalledTimes(1);
  });

  it("preserves the server's compensation state instead of claiming enqueue success", async () => {
    const state = setup(kind); mocks.retry.mockResolvedValueOnce({ taskId: 7, status: "publish_failed" });
    state.refresh.mockRejectedValueOnce(new Error("refresh unavailable")); await state.run();
    expect(mocks.success).not.toHaveBeenCalled(); expect(mocks.warning).toHaveBeenCalledWith("审查任务已保存，等待消息投递补偿");
    expect(mocks.warning).not.toHaveBeenCalledWith(expect.stringContaining("已重新入队"));
  });

  it("does not accept an acknowledgement for another task", async () => {
    const state = setup(kind); mocks.retry.mockResolvedValueOnce({ ...response, taskId: 8 }); await state.run();
    expect(mocks.success).not.toHaveBeenCalled(); expect(state.refresh).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining("任务标识不一致"));
  });

  it("unlocks on cancellation and allows an explicit retry after a current write failure", async () => {
    const state = setup(kind); mocks.confirm.mockRejectedValueOnce("cancel"); await state.run(); expect(mocks.retry).not.toHaveBeenCalled();
    mocks.retry.mockRejectedValueOnce(new Error("retry unavailable")); await state.run(); expect(mocks.error).toHaveBeenCalledWith("retry unavailable");
    expect(state.busy()).toBe(false); await state.run(); expect(mocks.retry).toHaveBeenCalledTimes(2); expect(mocks.success).toHaveBeenCalledTimes(1);
  });

  it("still supports re-evaluating a superseded task", async () => {
    const state = setup(kind); state.activeTask().status = "superseded"; await state.run();
    expect(mocks.confirm.mock.calls[0]![0]).toContain("最新提交"); expect(mocks.retry).toHaveBeenCalledWith(7); expect(mocks.success).toHaveBeenCalledTimes(1);
  });
});
