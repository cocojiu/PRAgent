import { beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope } from "vue";
import { fetchGithubCommentPublicationBatches, publishGithubComments } from "@/api/reviews";
import { useReviewDetailGithubComments } from "./useReviewDetailGithubComments";
import type { GithubCommentPublicationBatch, GithubCommentPublicationHistory } from "@/types";

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}));

vi.mock("@/api/reviews", () => ({
  fetchGithubCommentPreview: vi.fn(),
  fetchGithubCommentPublicationBatches: vi.fn(),
  fetchGithubCommentPublicationItems: vi.fn(),
  publishGithubComments: vi.fn()
}));
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: messages
}));

const historyResponse = (
  page: number,
  total = 12,
  status: string | undefined = undefined
): GithubCommentPublicationHistory => ({
  taskId: 521,
  total,
  page,
  pageSize: 5,
  status,
  batches: []
});

const historyBatch = (status: string): GithubCommentPublicationBatch => ({
  batchId: 99,
  status,
  totalFindings: 3,
  attemptedCount: status === "completed" ? 3 : 0,
  succeededCount: status === "completed" ? 3 : 0,
  failedCount: 0,
  skippedCount: 0,
  createdAt: "2026-07-08 15:30:00",
  completedAt: status === "completed" ? "2026-07-08 15:30:03" : undefined,
  items: []
});

describe("useReviewDetailGithubComments", () => {
  const fetchHistory = vi.mocked(fetchGithubCommentPublicationBatches);
  const publishComments = vi.mocked(publishGithubComments);

  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("loads publication history with bounded pagination parameters", async () => {
    fetchHistory.mockResolvedValue(historyResponse(2, 17, "completed"));

    const comments = useReviewDetailGithubComments();
    await comments.loadGithubCommentPublicationHistory(521, {
      page: 2,
      status: "completed"
    });

    expect(fetchHistory).toHaveBeenCalledWith(521, {
      page: 2,
      pageSize: 5,
      status: "completed"
    }, { signal: expect.any(AbortSignal) });
    expect(comments.historyPage.value).toBe(2);
    expect(comments.historyPageSize).toBe(5);
    expect(comments.historyStatus.value).toBe("completed");
    expect(comments.publicationHistoryTotal.value).toBe(17);
  });

  it("resets publication history pagination when clearing preview state", async () => {
    fetchHistory
      .mockResolvedValueOnce(historyResponse(3, 21))
      .mockResolvedValueOnce(historyResponse(1, 21));

    const comments = useReviewDetailGithubComments();
    await comments.loadGithubCommentPublicationHistory(521, { page: 3 });
    comments.clearGithubCommentPreviewAndHistory();
    await comments.loadGithubCommentPublicationHistory(521);

    expect(fetchHistory).toHaveBeenLastCalledWith(521, {
      page: 1,
      pageSize: 5,
      status: undefined
    }, { signal: expect.any(AbortSignal) });
    expect(comments.historyPage.value).toBe(1);
  });

  it("shows queued feedback after scheduling GitHub comment publish", async () => {
    publishComments.mockResolvedValue({
      taskId: 521,
      batchId: 99,
      status: "queued",
      totalFindings: 3,
      attemptedCount: 0,
      succeededCount: 0,
      failedCount: 0,
      skippedCount: 0,
      items: []
    });
    const afterPublish = vi.fn().mockResolvedValue(undefined);

    const comments = useReviewDetailGithubComments();
    await comments.publishGithubCommentsForTask(521, afterPublish);

    expect(publishComments).toHaveBeenCalledWith(521);
    expect(messages.success).toHaveBeenCalledWith("GitHub 评论回写已加入队列（批次 #99）");
    expect(afterPublish).toHaveBeenCalledOnce();
    expect(comments.githubCommentPublishResult.value?.status).toBe("queued");
    comments.stopGithubCommentPublishPolling();
  });

  it("polls queued publish batch until terminal history status", async () => {
    vi.useFakeTimers();
    publishComments.mockResolvedValue({
      taskId: 521,
      batchId: 99,
      status: "queued",
      totalFindings: 3,
      attemptedCount: 0,
      succeededCount: 0,
      failedCount: 0,
      skippedCount: 0,
      items: []
    });
    fetchHistory.mockResolvedValue({
      taskId: 521,
      total: 1,
      page: 1,
      pageSize: 5,
      batches: [historyBatch("completed")]
    });
    const afterPublish = vi.fn().mockResolvedValue(undefined);

    try {
      const comments = useReviewDetailGithubComments();
      await comments.publishGithubCommentsForTask(521, afterPublish);
      expect(afterPublish).toHaveBeenCalledOnce();

      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchHistory).toHaveBeenCalledWith(521, {
        page: 1,
        pageSize: 5
      }, { signal: expect.any(AbortSignal) });
      expect(comments.githubCommentPublishResult.value?.status).toBe("completed");
      expect(comments.githubCommentPublishResult.value?.succeededCount).toBe(3);
      expect(afterPublish).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("ignores an old summary after switching tasks", async () => {
    let resolveOld!: (value: GithubCommentPublicationHistory) => void;
    fetchHistory.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    fetchHistory.mockResolvedValueOnce({ ...historyResponse(1), taskId: 522 });
    const comments = useReviewDetailGithubComments();
    const old = comments.loadGithubCommentPublicationHistory(521);
    const signal = fetchHistory.mock.calls[0]![2]!.signal!;
    await comments.loadGithubCommentPublicationHistory(522);
    expect(signal.aborted).toBe(true);
    resolveOld(historyResponse(2));
    await old;
    expect(comments.githubCommentPublicationHistory.value?.taskId).toBe(522);
    expect(comments.historyPage.value).toBe(1);
  });

  it("cancels a summary and ignores an error after scope disposal", async () => {
    let rejectRequest!: (reason: unknown) => void;
    fetchHistory.mockImplementationOnce(() => new Promise((_, reject) => { rejectRequest = reject; }));
    const scope = effectScope();
    const comments = scope.run(() => useReviewDetailGithubComments())!;
    const request = comments.loadGithubCommentPublicationHistory(521);
    const signal = fetchHistory.mock.calls[0]![2]!.signal!;
    scope.stop();
    rejectRequest(new Error("late network failure"));
    await request;
    expect(signal.aborted).toBe(true);
    expect(comments.githubCommentPublicationHistory.value).toBeNull();
    expect(comments.historyError.value).toBe("");
  });
});
