import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReviewTaskDetail } from "@/types";

const { fetchReviewDetail, fetchReviewStatus, showError } = vi.hoisted(() => ({
  fetchReviewDetail: vi.fn(),
  fetchReviewStatus: vi.fn(),
  showError: vi.fn()
}));

vi.mock("@/api/reviews", () => ({ fetchReviewDetail, fetchReviewStatus }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: { error: showError }
}));
vi.mock("../reviewDetailTaskMappers", () => ({
  applyReviewStatusSnapshot: vi.fn(),
  normalizeReviewTaskDetail: (task: ReviewTaskDetail) => task
}));

import { useReviewDetailLoader } from "./useReviewDetailLoader";

describe("useReviewDetailLoader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("keeps the latest task when an older detail response arrives last", async () => {
    let taskId = 1;
    const first = deferred<ReviewTaskDetail>();
    const second = deferred<ReviewTaskDetail>();
    fetchReviewDetail.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise);
    const loader = createLoader(() => taskId);

    const firstLoad = loader.loadDetail();
    taskId = 2;
    const secondLoad = loader.loadDetail();
    second.resolve(task(2));
    await secondLoad;

    expect(loader.selectedTask.value?.id).toBe(2);
    expect(loader.loading.value).toBe(false);

    first.resolve(task(1));
    await firstLoad;

    expect(loader.selectedTask.value?.id).toBe(2);
    expect(loader.errorMessage.value).toBe("");
  });

  it("suppresses stale errors and invalidates pending work on cancellation", async () => {
    const request = deferred<ReviewTaskDetail>();
    fetchReviewDetail.mockReturnValueOnce(request.promise);
    const loader = createLoader(() => 1);
    const pending = loader.loadDetail();

    loader.cancelPendingRequests();
    request.reject(new Error("stale"));
    await pending;

    expect(loader.selectedTask.value).toBeNull();
    expect(loader.errorMessage.value).toBe("");
    expect(loader.loading.value).toBe(false);
    expect(showError).not.toHaveBeenCalled();
  });
});

const createLoader = (getTaskId: () => number) =>
  useReviewDetailLoader({
    clearGithubCommentPreviewAndHistory: vi.fn(),
    getTaskId,
    isTerminalReviewStatus: () => false,
    maxPollFailures: 3,
    resetGithubCommentPublishResult: vi.fn(),
    stopPolling: vi.fn(),
    syncPolling: vi.fn()
  });

const task = (id: number) => ({ id, status: "pending" }) as ReviewTaskDetail;

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
};
