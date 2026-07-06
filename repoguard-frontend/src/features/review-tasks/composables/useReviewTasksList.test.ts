import { afterEach, describe, expect, it, vi } from "vitest";
import { useReviewTasksList } from "./useReviewTasksList";
import type { ReviewTask } from "@/types";

const reviewApi = vi.hoisted(() => ({
  fetchReviewRepositories: vi.fn(),
  fetchReviews: vi.fn()
}));

const messages = vi.hoisted(() => ({
  error: vi.fn()
}));

vi.mock("@/api/reviews", () => reviewApi);
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: messages
}));

describe("useReviewTasksList", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loads the task page and repository filter options through separate lightweight requests", async () => {
    reviewApi.fetchReviews.mockResolvedValue({
      items: [reviewTask],
      total: 26
    });
    reviewApi.fetchReviewRepositories.mockResolvedValue(["repo-guard", "repoguard-agent"]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();

    expect(reviewApi.fetchReviews).toHaveBeenCalledTimes(1);
    expect(reviewApi.fetchReviews).toHaveBeenCalledWith({
      page: 1,
      pageSize: 8,
      repository: "",
      status: "",
      riskLevel: "",
      triggerSource: "",
      keyword: ""
    });
    expect(reviewApi.fetchReviews).not.toHaveBeenCalledWith(expect.objectContaining({ pageSize: 100 }));
    expect(reviewApi.fetchReviewRepositories).toHaveBeenCalledTimes(1);
    expect(list.reviewTasks.value).toEqual([reviewTask]);
    expect(list.totalTasks.value).toBe(26);
    expect(list.repositories.value).toEqual(["repo-guard", "repoguard-agent"]);
    expect(list.loading.value).toBe(false);
  });
});

const reviewTask: ReviewTask = {
  id: 7,
  prNumber: 42,
  title: "Keep review list light",
  repository: "repo-guard",
  organization: "codex",
  commit: "abc123",
  branch: "main",
  status: "completed",
  riskLevel: "low",
  mqRetries: 0,
  llmStatus: "completed",
  source: "github_webhook",
  triggerSource: "github_webhook",
  createdAt: "2026-07-06 15:58:00",
  duration: "1 min 05 sec",
  humanReviewRequired: false,
  humanReviewStatus: "not_required"
};

const flushAsync = () => new Promise(resolve => window.setTimeout(resolve, 0));
