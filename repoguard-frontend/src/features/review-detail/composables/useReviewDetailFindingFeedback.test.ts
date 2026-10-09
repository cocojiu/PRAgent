import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { computed, effectScope, ref } from "vue";
import type { FindingFeedbackResponse } from "@/api/generated/reviewDetailTypes";
import type { ReviewTaskDetail } from "@/types";
import { useReviewDetailFindingFeedback } from "./useReviewDetailFindingFeedback";

const mocks = vi.hoisted(() => ({ prompt: vi.fn(), update: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("@/api/reviews", () => ({ updateFindingFeedback: mocks.update }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: mocks }));
vi.mock("element-plus/es/components/message-box/index.mjs", () => ({ ElMessageBox: { prompt: mocks.prompt } }));

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const task = (id = 7) => ({ id, commit: `head-${id}`, archived: false, findings: [{ id: 31, feedbackStatus: "unreviewed" }] }) as ReviewTaskDetail;
const response = { taskId: 7, findingId: 31, feedbackStatus: "valid" } satisfies FindingFeedbackResponse;
let scope: ReturnType<typeof effectScope>;
const setup = () => {
  scope = effectScope();
  const selectedTask = ref<ReviewTaskDetail | null>(task());
  const allowed = ref(true);
  const preview = vi.fn().mockResolvedValue(undefined);
  const reset = vi.fn();
  const feedback = scope.run(() => useReviewDetailFindingFeedback({ selectedTask,
    canManage: computed(() => allowed.value), isTerminalTask: computed(() => true),
    findingFeedbackPromptTitle: () => "确认有效", loadGithubCommentPreview: preview, resetGithubCommentPublishResult: reset
  }))!;
  return { ...feedback, selectedTask, allowed, preview, reset };
};
beforeEach(() => { vi.resetAllMocks(); mocks.prompt.mockResolvedValue({ value: "" }); mocks.update.mockResolvedValue(response); });
afterEach(() => scope?.stop());

describe("finding feedback context", () => {
  it("holds the busy guard while the prompt is open and submits only once", async () => {
    const prompt = deferred<{ value: string }>(); mocks.prompt.mockReturnValueOnce(prompt.promise);
    const state = setup();
    const pending = state.submitFindingFeedback(31, "valid");
    await state.submitFindingFeedback(31, "valid");
    expect(mocks.prompt).toHaveBeenCalledOnce(); expect(state.feedbackSavingId.value).toBe(31);
    prompt.resolve({ value: " confirmed " }); await pending;
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(7, 31, { status: "valid", note: "confirmed" });
    expect(state.selectedTask.value?.findings[0]?.feedbackStatus).toBe("valid");
    expect(state.reset).toHaveBeenCalledOnce(); expect(state.preview).toHaveBeenCalledExactlyOnceWith(7);
    expect(state.feedbackSavingId.value).toBeNull();
  });

  it.each(["task", "commit", "permission", "archive", "route", "scope", "finding"])("does not write when %s changes while prompting", async kind => {
    const prompt = deferred<{ value: string }>(); mocks.prompt.mockReturnValueOnce(prompt.promise);
    const state = setup(); const pending = state.submitFindingFeedback(31, "valid");
    if (kind === "task") state.selectedTask.value = task(8);
    if (kind === "commit") state.selectedTask.value!.commit = "new-head";
    if (kind === "permission") state.allowed.value = false;
    if (kind === "archive") state.selectedTask.value!.archived = true;
    if (kind === "route") state.cancelFindingFeedback();
    if (kind === "scope") scope.stop();
    if (kind === "finding") state.selectedTask.value!.findings = [];
    prompt.resolve({ value: "" }); await pending;
    expect(mocks.update).not.toHaveBeenCalled(); expect(mocks.success).not.toHaveBeenCalled();
    expect(state.feedbackSavingId.value).toBeNull();
  });

  it("ignores an old write response without clearing a newer prompt or current preview", async () => {
    const write = deferred<FindingFeedbackResponse>(); mocks.update.mockReturnValueOnce(write.promise);
    const state = setup(); const old = state.submitFindingFeedback(31, "valid"); await Promise.resolve();
    state.selectedTask.value = task(8);
    const prompt = deferred<{ value: string }>(); mocks.prompt.mockReturnValueOnce(prompt.promise);
    const newer = state.submitFindingFeedback(31, "valid");
    write.resolve(response); await old;
    expect(state.feedbackSavingId.value).toBe(31);
    expect(state.selectedTask.value?.findings[0]?.feedbackStatus).toBe("unreviewed");
    expect(state.reset).not.toHaveBeenCalled(); expect(state.preview).not.toHaveBeenCalled();
    expect(mocks.success).not.toHaveBeenCalled();
    prompt.reject("cancel"); await newer; expect(state.feedbackSavingId.value).toBeNull();
  });

  it("keeps a successful feedback write successful when the follow-up preview rejects", async () => {
    const state = setup(); state.preview.mockRejectedValueOnce(new Error("preview offline"));
    await state.submitFindingFeedback(31, "valid");
    expect(state.selectedTask.value?.findings[0]?.feedbackStatus).toBe("valid");
    expect(mocks.success).toHaveBeenCalledWith("确认有效");
    expect(mocks.warning).toHaveBeenCalledWith("判定已提交，评论预览刷新失败：preview offline");
    expect(mocks.error).not.toHaveBeenCalled();
  });

  it("ignores errors and does not refresh after disposal during a write", async () => {
    const write = deferred<FindingFeedbackResponse>(); mocks.update.mockReturnValueOnce(write.promise);
    const state = setup(); const pending = state.submitFindingFeedback(31, "valid"); await Promise.resolve();
    scope.stop(); write.reject(new Error("late error")); await pending;
    expect(mocks.error).not.toHaveBeenCalled(); expect(state.preview).not.toHaveBeenCalled();
  });

  it("reports a write failure and allows a subsequent submission", async () => {
    mocks.update.mockRejectedValueOnce(new Error("write failed")); const state = setup();
    await state.submitFindingFeedback(31, "valid");
    expect(mocks.error).toHaveBeenCalledWith("write failed"); expect(state.reset).not.toHaveBeenCalled();
    await state.submitFindingFeedback(31, "valid"); expect(mocks.update).toHaveBeenCalledTimes(2);
    expect(mocks.success).toHaveBeenCalledOnce();
  });

  it("does not apply a response belonging to another finding", async () => {
    mocks.update.mockResolvedValueOnce({ ...response, findingId: 32 }); const state = setup();
    await state.submitFindingFeedback(31, "valid");
    expect(state.selectedTask.value?.findings[0]?.feedbackStatus).toBe("unreviewed");
    expect(state.reset).not.toHaveBeenCalled(); expect(mocks.warning).toHaveBeenCalledOnce();
  });
});
