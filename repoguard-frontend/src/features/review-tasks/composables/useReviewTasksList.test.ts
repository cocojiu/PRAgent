import { afterEach, describe, expect, it, vi } from "vitest";
import { useReviewTasksList } from "./useReviewTasksList";
import type { ReviewTask } from "@/types";
import { statusClass, statusText } from "@/utils/status";
import {
  canRetryReviewTask,
  reviewTaskRetryText,
  reviewTaskRetryTooltip
} from "../reviewTaskDisplayMappers";

const reviewApi = vi.hoisted(() => ({
  fetchReviewListSummary: vi.fn(),
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
    reviewApi.fetchReviewRepositories.mockResolvedValue(["codex/repo-guard", "openai/repo-guard"]);

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
      keyword: "",
      cursorCreatedAt: undefined,
      cursorId: undefined,
      totalHint: undefined
    });
    expect(reviewApi.fetchReviews).not.toHaveBeenCalledWith(expect.objectContaining({ pageSize: 100 }));
    expect(reviewApi.fetchReviewRepositories).toHaveBeenCalledTimes(1);
    expect(list.reviewTasks.value).toEqual([reviewTask]);
    expect(list.totalTasks.value).toBe(26);
    expect(list.repositories.value).toEqual(["codex/repo-guard", "openai/repo-guard"]);
    expect(list.loading.value).toBe(false);
  });

  it("builds the metric cards from the server-side summary under the current filters", async () => {
    reviewApi.fetchReviews.mockResolvedValue({
      items: [reviewTask],
      total: 260
    });
    reviewApi.fetchReviewListSummary.mockResolvedValue({
      total: 260,
      highRisk: 13,
      failed: 26,
      averageDurationSeconds: 95
    });
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();

    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledTimes(1);
    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledWith({
      repository: "",
      status: "",
      riskLevel: "",
      triggerSource: "",
      keyword: ""
    });
    const [totalMetric, highRiskMetric, failedMetric, durationMetric] = list.taskSummaryMetrics.value;
    expect(totalMetric.value).toBe("260");
    expect(highRiskMetric.value).toBe("13");
    expect(highRiskMetric.note).toBe("5% 占比");
    expect(failedMetric.value).toBe("26");
    expect(failedMetric.note).toBe("10% 占比");
    expect(durationMetric.value).toBe("1 分 35 秒");
  });

  it("does not reload the summary when only the page changes", async () => {
    reviewApi.fetchReviews.mockResolvedValue({
      items: [reviewTask],
      total: 26
    });
    reviewApi.fetchReviewListSummary.mockResolvedValue({
      total: 26,
      highRisk: 2,
      failed: 1,
      averageDurationSeconds: 65
    });
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();

    list.currentPage.value = 2;
    await flushAsync();

    expect(reviewApi.fetchReviews).toHaveBeenCalledTimes(2);
    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledTimes(1);
  });

  it("refreshes the summary with the list and falls back to zeroed metrics on failure", async () => {
    reviewApi.fetchReviews.mockResolvedValue({
      items: [reviewTask],
      total: 26
    });
    reviewApi.fetchReviewListSummary
      .mockResolvedValueOnce({
        total: 26,
        highRisk: 2,
        failed: 1,
        averageDurationSeconds: 65
      })
      .mockRejectedValueOnce(new Error("summary failed"));
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();
    expect(list.taskSummaryMetrics.value[0].value).toBe("26");

    list.refreshTasks();
    await flushAsync();

    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledTimes(2);
    expect(list.taskSummaryMetrics.value[0].value).toBe("0");
    expect(list.taskSummaryMetrics.value[3].value).toBe("0 分 0 秒");
  });

  it("uses the previous page tail as cursor when loading the next page", async () => {
    const secondTask = { ...reviewTask, id: 8, createdAt: "2026-07-06 15:57:00" };
    reviewApi.fetchReviews
      .mockResolvedValueOnce({
        items: [reviewTask, secondTask],
        total: 26
      })
      .mockResolvedValueOnce({
        items: [],
        total: 26
      });
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();

    list.currentPage.value = 2;
    await flushAsync();

    expect(reviewApi.fetchReviews).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 2,
      cursorCreatedAt: secondTask.createdAt,
      cursorId: secondTask.id,
      totalHint: 26
    }));
  });

  it("clears remembered cursors before refreshing the task list", async () => {
    reviewApi.fetchReviews
      .mockResolvedValueOnce({
        items: [reviewTask],
        total: 26
      })
      .mockResolvedValueOnce({
        items: [],
        total: 26
      })
      .mockResolvedValueOnce({
        items: [],
        total: 26
      });
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();
    list.currentPage.value = 2;
    await flushAsync();

    list.refreshTasks();
    await flushAsync();

    expect(reviewApi.fetchReviews).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 2,
      cursorCreatedAt: undefined,
      cursorId: undefined,
      totalHint: undefined
    }));
  });

  it("exposes superseded tasks as retryable against the latest pull request head", () => {
    const supersededTask: ReviewTask = {
      ...reviewTask,
      status: "superseded",
      failureSuggestion: "请按最新提交重新发起审查。"
    };

    expect(canRetryReviewTask(supersededTask)).toBe(true);
    expect(reviewTaskRetryText(supersededTask)).toBe("按最新提交重评");
    expect(reviewTaskRetryTooltip(supersededTask)).toContain("最新提交");
    expect(statusText("superseded")).toBe("已过期");
    expect(statusClass("superseded")).toBe("warning");
  });
});

const reviewTask: ReviewTask = {
  id: 7,
  prNumber: 42,
  title: "Keep review list light",
  repository: "repo-guard",
  organization: "codex",
  commit: "0123456789abcdef0123456789abcdef01234567",
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
