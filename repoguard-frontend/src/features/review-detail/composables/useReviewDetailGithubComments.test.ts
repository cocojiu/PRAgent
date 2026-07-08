import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchGithubCommentPublicationHistory, publishGithubComments } from "@/api/reviews";
import { useReviewDetailGithubComments } from "./useReviewDetailGithubComments";
import type { GithubCommentPublicationBatch, GithubCommentPublicationHistory } from "@/types";

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}));

vi.mock("@/api/reviews", () => ({
  fetchGithubCommentPreview: vi.fn(),
  fetchGithubCommentPublicationHistory: vi.fn(),
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
  const fetchHistory = vi.mocked(fetchGithubCommentPublicationHistory);
  const publishComments = vi.mocked(publishGithubComments);

  beforeEach(() => {
    vi.clearAllMocks();
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
    });
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
    });
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
      });
      expect(comments.githubCommentPublishResult.value?.status).toBe("completed");
      expect(comments.githubCommentPublishResult.value?.succeededCount).toBe(3);
      expect(afterPublish).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
