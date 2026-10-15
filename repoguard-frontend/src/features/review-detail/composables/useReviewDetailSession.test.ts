import { effectScope, ref } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReviewTaskDetail } from "@/types";
import { setActiveTenant } from "@/stores/tenantContext";
import { useReviewDetailSession } from "./useReviewDetailSession";

const api = vi.hoisted(() => ({
  detail: vi.fn(), status: vi.fn(), attempts: vi.fn().mockResolvedValue([]),
  comparison: vi.fn(), findings: vi.fn()
}));
vi.mock("@/api/reviews", () => ({
  fetchReviewDetail: api.detail, fetchReviewStatus: api.status, fetchReviewExecutionAttempts: api.attempts,
  fetchReviewAttemptComparison: api.comparison, fetchReviewFindings: api.findings,
  fetchReviewChangedFiles: vi.fn(), fetchReviewMissingTests: vi.fn(), fetchReviewTimeline: vi.fn()
}));
vi.mock("./useReviewDetailGithubComments", () => ({ useReviewDetailGithubComments: () => ({
  clearGithubCommentState: vi.fn(), stopGithubCommentPublishPolling: vi.fn(),
  clearGithubCommentPreviewAndHistory: vi.fn(), resetGithubCommentPublishResult: vi.fn()
}) }));
vi.mock("../reviewDetailTaskMappers", () => ({
  normalizeReviewTaskDetail: (task: ReviewTaskDetail) => task,
  applyReviewStatusSnapshot: (task: ReviewTaskDetail, status: object) => ({ ...task, ...status }),
  toReviewFindingViewModel: (value: unknown) => value
}));

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const task = (id: number, title = "current") => ({ id, title, status: "completed", findings: [] } as unknown as ReviewTaskDetail);
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const scopes: ReturnType<typeof effectScope>[] = [];

describe("detail session ownership", () => {
  afterEach(() => {
    scopes.splice(0).forEach(scope => scope.stop());
    setActiveTenant();
    vi.clearAllMocks();
  });
  const setup = () => {
    const id = ref(9);
    const scope = effectScope(); scopes.push(scope);
    const session = scope.run(() => useReviewDetailSession({ getTaskId: () => id.value }))!;
    return { id, scope, session };
  };

  it("cancels old routes and ignores late failures", async () => {
    const first = deferred<ReviewTaskDetail>(), second = deferred<ReviewTaskDetail>();
    api.detail.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { id, session } = setup();
    const signal = api.detail.mock.calls[0][1].signal as AbortSignal;
    id.value = 10;
    expect(signal.aborted).toBe(true);
    second.resolve(task(10)); await flush();
    first.reject(new Error("old route")); await flush();
    expect(session.selectedTask.value?.id).toBe(10);
    expect(session.errorMessage.value).toBe("");
  });

  it("invalidates sections when tenant changes with the same task id", async () => {
    api.detail.mockResolvedValueOnce(task(9, "tenant A"));
    const { session } = setup(); await flush();
    const oldFindings = deferred<{ items: unknown[]; total: number }>();
    api.findings.mockReturnValueOnce(oldFindings.promise);
    const pending = session.loadFindingsFirstPage();
    const signal = api.findings.mock.calls[0][2].signal as AbortSignal;
    api.detail.mockResolvedValueOnce(task(9, "tenant B"));
    setActiveTenant("tenant-b");
    expect(signal.aborted).toBe(true);
    expect(session.selectedTask.value).toBeNull();
    await flush();
    oldFindings.resolve({ items: [{ id: 999 }], total: 1 }); await pending;
    expect(session.selectedTask.value?.title).toBe("tenant B");
    expect(session.selectedTask.value?.findings).toEqual([]);
  });

  it("disposal prevents late summary and attempt responses from writing state", async () => {
    const pending = deferred<ReviewTaskDetail>();
    api.detail.mockReturnValueOnce(pending.promise);
    const { scope, session } = setup();
    const signal = api.detail.mock.calls[0][1].signal as AbortSignal;
    scope.stop();
    expect(signal.aborted).toBe(true);
    pending.resolve(task(9)); await flush();
    expect(session.selectedTask.value).toBeNull();
    expect(api.attempts).not.toHaveBeenCalled();
  });

  it("refreshes human review status without discarding loaded sections", async () => {
    api.detail.mockResolvedValueOnce(task(9));
    const { session } = setup(); await flush();
    session.selectedTask.value!.findings = [{ id: 123 }] as ReviewTaskDetail["findings"];
    api.status.mockResolvedValueOnce({ status: "approved", humanReviewStatus: "approved" });
    await session.refreshAfterHumanReview();
    expect(session.selectedTask.value?.status).toBe("approved");
    expect(session.selectedTask.value?.findings[0]?.id).toBe(123);
    expect(api.detail).toHaveBeenCalledTimes(1);
  });
});
