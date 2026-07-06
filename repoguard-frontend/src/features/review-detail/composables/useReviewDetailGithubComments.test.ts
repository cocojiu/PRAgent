import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchGithubCommentPublicationHistory } from "@/api/reviews";
import { useReviewDetailGithubComments } from "./useReviewDetailGithubComments";
import type { GithubCommentPublicationHistory } from "@/types";

vi.mock("@/api/reviews", () => ({
  fetchGithubCommentPreview: vi.fn(),
  fetchGithubCommentPublicationHistory: vi.fn(),
  publishGithubComments: vi.fn()
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

describe("useReviewDetailGithubComments", () => {
  const fetchHistory = vi.mocked(fetchGithubCommentPublicationHistory);

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
});
