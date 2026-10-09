import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { computed, effectScope, ref } from "vue";
import type { GithubCommentPreview, ReviewTaskDetail } from "@/types";
import { useReviewDetailGithubCommentPublishConfirm } from "./useReviewDetailGithubCommentPublishConfirm";

const mocks = vi.hoisted(() => ({ confirm: vi.fn() }));
vi.mock("element-plus/es/components/message-box/index.mjs", () => ({ ElMessageBox: mocks }));
let scope: ReturnType<typeof effectScope>;
const setup = () => {
  scope = effectScope();
  const selectedTask = ref({ id: 7, commit: "first-head", prNumber: 3 } as ReviewTaskDetail);
  const githubCommentPreview = ref({ taskId: 7, commentableCount: 2 } as GithubCommentPreview);
  const allowed = ref(true); const ready = ref(true); const publishingComments = ref(false);
  const publish = vi.fn().mockResolvedValue(undefined); const preview = vi.fn(); const history = vi.fn();
  const confirm = scope.run(() => useReviewDetailGithubCommentPublishConfirm({ selectedTask,
    githubCommentPreview, canManage: computed(() => allowed.value), canPublishGithubComments: computed(() => ready.value),
    writebackCheck: computed(() => undefined), publishingComments, publishGithubCommentsForTask: publish,
    loadGithubCommentPreview: preview, loadGithubCommentPublicationHistory: history
  }))!;
  return { ...confirm, selectedTask, githubCommentPreview, allowed, ready, publishingComments, publish, preview, history };
};
beforeEach(() => { vi.resetAllMocks(); mocks.confirm.mockResolvedValue(undefined); });
afterEach(() => scope?.stop());

it("permits one confirmed write for the current preview and refreshes the same task", async () => {
  let resolve!: () => void; mocks.confirm.mockReturnValueOnce(new Promise<void>(yes => { resolve = yes; }));
  const state = setup(); const pending = state.confirmPublishGithubComments(); await state.confirmPublishGithubComments();
  expect(mocks.confirm).toHaveBeenCalledOnce(); resolve(); await pending;
  expect(state.publish).toHaveBeenCalledExactlyOnceWith(7, expect.any(Function));
  await state.publish.mock.calls[0]![1]();
  expect(state.preview).toHaveBeenCalledWith(7); expect(state.history).toHaveBeenCalledWith(7);
});

it.each(["task", "commit", "preview", "permission", "refresh", "write", "scope"])("does not publish if %s changes during confirmation", async kind => {
  let resolve!: () => void; mocks.confirm.mockReturnValueOnce(new Promise<void>(yes => { resolve = yes; }));
  const state = setup(); const pending = state.confirmPublishGithubComments();
  if (kind === "task") state.selectedTask.value = { ...state.selectedTask.value, id: 8 };
  if (kind === "commit") state.selectedTask.value.commit = "second-head";
  if (kind === "preview") state.githubCommentPreview.value = { ...state.githubCommentPreview.value };
  if (kind === "permission") state.allowed.value = false;
  if (kind === "refresh") state.ready.value = false;
  if (kind === "write") state.publishingComments.value = true;
  if (kind === "scope") scope.stop();
  resolve(); await pending; expect(state.publish).not.toHaveBeenCalled();
});

it("does not start follow-up reads for a task left after publishing", async () => {
  const state = setup(); await state.confirmPublishGithubComments();
  state.selectedTask.value = { ...state.selectedTask.value, id: 8 };
  await state.publish.mock.calls[0]![1]();
  expect(state.preview).not.toHaveBeenCalled(); expect(state.history).not.toHaveBeenCalled();
});
