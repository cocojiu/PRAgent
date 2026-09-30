import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, nextTick, type App } from "vue";
import { RequestError } from "@/utils/errors";
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

const requestOptions = { signal: expect.any(AbortSignal) };
const mountedApps = new Set<App>();

describe("useReviewTasksList", () => {
  afterEach(() => {
    for (const app of mountedApps) app.unmount();
    mountedApps.clear();
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("loads the pending queue and summary with the same server filter, resetting page and cursors", async () => {
    vi.useFakeTimers();
    reviewApi.fetchReviews.mockResolvedValue({ items: [], total: 26, nextCursor: "old-cursor", hasMore: true });
    reviewApi.fetchReviewListSummary.mockResolvedValue({ total: 26, highRisk: 0, failed: 0, averageDurationSeconds: 1 });
    const list = useReviewTasksList();
    await list.loadTasks();
    list.currentPage.value = 2;
    await vi.advanceTimersByTimeAsync(1);
    list.repoFilter.value = "owner/repo";
    list.riskFilter.value = "high";
    list.statusFilter.value = "pending_human_review";
    await vi.advanceTimersByTimeAsync(400);
    expect(list.currentPage.value).toBe(1);
    expect(reviewApi.fetchReviews).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 1, repository: "owner/repo", riskLevel: "high", status: "pending_human_review", cursor: undefined
    }), requestOptions);
    expect(reviewApi.fetchReviewListSummary).toHaveBeenLastCalledWith(expect.objectContaining({
      repository: "owner/repo", riskLevel: "high", status: "pending_human_review"
    }), requestOptions);
    list.currentPage.value = 2;
    await vi.advanceTimersByTimeAsync(1);
    expect(reviewApi.fetchReviews).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, cursor: undefined }), requestOptions);
  });

  it("returns to a valid page when reviewed tasks disappear from the queue", async () => {
    vi.useFakeTimers();
    reviewApi.fetchReviews.mockResolvedValue({ items: [], total: 0 });
    reviewApi.fetchReviewListSummary.mockResolvedValue({ total: 0, highRisk: 0, failed: 0, averageDurationSeconds: 0 });
    const list = useReviewTasksList();
    list.statusFilter.value = "pending_human_review";
    await vi.advanceTimersByTimeAsync(400);
    list.currentPage.value = 3;
    await vi.advanceTimersByTimeAsync(1);
    expect(list.currentPage.value).toBe(1);
    expect(list.totalTasks.value).toBe(0);
    expect(list.reviewTasks.value).toEqual([]);
  });

  it("does not show an old response during a pending-queue filter debounce", async () => {
    vi.useFakeTimers();
    let finishOld!: (page: { items: ReviewTask[]; total: number }) => void;
    reviewApi.fetchReviews.mockReturnValueOnce(new Promise(resolve => { finishOld = resolve; }));
    const list = useReviewTasksList();
    const oldRequest = list.loadTasks();
    list.statusFilter.value = "pending_human_review";
    await vi.advanceTimersByTimeAsync(1);
    finishOld({ items: [reviewTask], total: 99 });
    await oldRequest;
    expect(list.reviewTasks.value).toEqual([]);
    expect(list.totalTasks.value).toBe(0);
    expect(list.taskSummaryMetrics.value[0].value).toBe("—");
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
      cursor: undefined
    }, requestOptions);
    expect(reviewApi.fetchReviews).not.toHaveBeenCalledWith(expect.objectContaining({ pageSize: 100 }), requestOptions);
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
    }, requestOptions);
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

  it("refreshes the summary with the list and retains the last successful values on failure", async () => {
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
    expect(list.taskSummaryMetrics.value[0].value).toBe("26");
    expect(list.taskSummaryMetrics.value[3].value).toBe("1 分 5 秒");
  });

  it("shows an unknown state instead of misleading zeroes when no summary has loaded", async () => {
    reviewApi.fetchReviews.mockResolvedValue({ items: [], total: 0 });
    reviewApi.fetchReviewListSummary.mockRejectedValue(new Error("summary unavailable"));
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);

    const list = useReviewTasksList();
    list.initializeReviewTasksList();
    await flushAsync();

    expect(list.taskSummaryMetrics.value.map(metric => metric.value)).toEqual(["—", "—", "—", "—"]);
    expect(list.taskSummaryMetrics.value.every(metric => metric.note === "数据暂不可用")).toBe(true);
  });

  it("uses the server-issued cursor when loading the next page", async () => {
    const secondTask = { ...reviewTask, id: 8, createdAt: "2026-07-06 15:57:00" };
    reviewApi.fetchReviews
      .mockResolvedValueOnce({
        items: [reviewTask, secondTask],
        total: 26,
        nextCursor: "server-cursor-2",
        hasMore: true
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
      cursor: "server-cursor-2"
    }), requestOptions);
  });

  it("clears remembered cursors before refreshing the task list", async () => {
    reviewApi.fetchReviews
      .mockResolvedValueOnce({
        items: [reviewTask],
        total: 26,
        nextCursor: "server-cursor-2",
        hasMore: true
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
      cursor: undefined
    }), requestOptions);
  });

  it("aborts list and summary immediately during debounce and only applies the final filter", async () => {
    resetReviewApi();
    vi.useFakeTimers();
    const oldPage = deferred<{ items: ReviewTask[]; total: number }>();
    const oldSummary = deferred<typeof summaryFixture>();
    reviewApi.fetchReviews.mockReturnValueOnce(oldPage.promise).mockResolvedValue({ items: [reviewTask], total: 1 });
    reviewApi.fetchReviewListSummary.mockReturnValueOnce(oldSummary.promise).mockResolvedValue({ ...summaryFixture, total: 1 });
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);
    const { list } = mountList();
    list.initializeReviewTasksList();
    const oldTaskSignal = reviewApi.fetchReviews.mock.calls[0][1].signal as AbortSignal;
    const oldSummarySignal = reviewApi.fetchReviewListSummary.mock.calls[0][1].signal as AbortSignal;

    list.keyword.value = "first";
    await nextTick();
    expect(oldTaskSignal.aborted).toBe(true);
    expect(oldSummarySignal.aborted).toBe(true);
    expect(reviewApi.fetchReviews).toHaveBeenCalledTimes(1);
    list.keyword.value = "final";
    await nextTick();
    await vi.advanceTimersByTimeAsync(351);
    expect(reviewApi.fetchReviews).toHaveBeenCalledTimes(2);
    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledTimes(2);
    expect(reviewApi.fetchReviews).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: "final" }), requestOptions);
    oldPage.resolve({ items: [], total: 99 });
    oldSummary.resolve({ ...summaryFixture, total: 99 });
    await vi.advanceTimersByTimeAsync(1);
    expect(list.reviewTasks.value).toEqual([reviewTask]);
    expect(list.totalTasks.value).toBe(1);
    expect(list.taskSummaryMetrics.value[0].value).toBe("1");
    expect(list.errorMessage.value).toBe("");
    expect(messages.error).not.toHaveBeenCalled();
  });

  it("cancels only the list request when the page changes", async () => {
    resetReviewApi();
    reviewApi.fetchReviews.mockReturnValueOnce(new Promise(() => {})).mockResolvedValue({ items: [], total: 26 });
    reviewApi.fetchReviewListSummary.mockReturnValue(new Promise(() => {}));
    reviewApi.fetchReviewRepositories.mockResolvedValue([]);
    const { list } = mountList();
    list.initializeReviewTasksList();
    const listSignal = reviewApi.fetchReviews.mock.calls[0][1].signal as AbortSignal;
    const summarySignal = reviewApi.fetchReviewListSummary.mock.calls[0][1].signal as AbortSignal;
    list.currentPage.value = 2;
    await nextTick();
    expect(listSignal.aborted).toBe(true);
    expect(summarySignal.aborted).toBe(false);
    expect(reviewApi.fetchReviewListSummary).toHaveBeenCalledTimes(1);
  });

  it("keeps the newer request loading when the cancelled request finishes", async () => {
    resetReviewApi();
    const oldPage = deferred<{ items: ReviewTask[]; total: number }>();
    const newPage = deferred<{ items: ReviewTask[]; total: number }>();
    reviewApi.fetchReviews.mockReturnValueOnce(oldPage.promise).mockReturnValueOnce(newPage.promise);
    const { list } = mountList();
    const first = list.loadTasks();
    const second = list.loadTasks();
    expect(reviewApi.fetchReviews.mock.calls[0][1].signal.aborted).toBe(true);
    oldPage.reject(new RequestError("cancelled", { code: "REQUEST_ABORTED" }));
    await first;
    expect(list.loading.value).toBe(true);
    expect(list.errorMessage.value).toBe("");
    newPage.resolve({ items: [reviewTask], total: 1 });
    await second;
    expect(list.loading.value).toBe(false);
    expect(list.reviewTasks.value).toEqual([reviewTask]);
  });

  it.each([
    () => new RequestError("cancelled", { code: "REQUEST_ABORTED" }),
    () => new DOMException("cancelled", "AbortError")
  ])("suppresses cancellation errors and keeps the last successful page", async error => {
    resetReviewApi();
    reviewApi.fetchReviews.mockResolvedValueOnce({ items: [reviewTask], total: 1 }).mockRejectedValueOnce(error());
    reviewApi.fetchReviewRepositories.mockRejectedValueOnce(error());
    const { list } = mountList();
    await list.loadTasks();
    await list.loadTasks();
    await list.loadRepositories();
    expect(list.reviewTasks.value).toEqual([reviewTask]);
    expect(list.totalTasks.value).toBe(1);
    expect(list.errorMessage.value).toBe("");
    expect(list.loading.value).toBe(false);
    expect(messages.error).not.toHaveBeenCalled();
  });

  it("aborts every in-flight request and ignores all state writes after unmount", async () => {
    resetReviewApi();
    const page = deferred<{ items: ReviewTask[]; total: number }>();
    const summary = deferred<typeof summaryFixture>();
    const repositories = deferred<string[]>();
    reviewApi.fetchReviews.mockReturnValue(page.promise);
    reviewApi.fetchReviewListSummary.mockReturnValue(summary.promise);
    reviewApi.fetchReviewRepositories.mockReturnValue(repositories.promise);
    const { list, app } = mountList();
    list.initializeReviewTasksList();
    const signals = [reviewApi.fetchReviews.mock.calls[0][1].signal,
      reviewApi.fetchReviewListSummary.mock.calls[0][1].signal, reviewApi.fetchReviewRepositories.mock.calls[0][0].signal];
    app.unmount();
    mountedApps.delete(app);
    expect(signals.every(signal => signal.aborted)).toBe(true);
    page.resolve({ items: [reviewTask], total: 1 });
    summary.resolve(summaryFixture);
    repositories.resolve(["late/repository"]);
    await flushAsync();
    expect(list.reviewTasks.value).toEqual([]);
    expect(list.totalTasks.value).toBe(0);
    expect(list.repositories.value).toEqual([]);
    expect(list.taskSummaryMetrics.value[0].value).toBe("—");
    expect(list.loading.value).toBe(true);
    expect(messages.error).not.toHaveBeenCalled();
    list.refreshTasks();
    list.initializeReviewTasksList();
    expect(reviewApi.fetchReviews).toHaveBeenCalledTimes(1);
  });

  it("cancels replaced repository requests and rejects late option results", async () => {
    resetReviewApi();
    const oldRepositories = deferred<string[]>();
    reviewApi.fetchReviewRepositories.mockReturnValueOnce(oldRepositories.promise).mockResolvedValue(["current/repo"]);
    const { list } = mountList();
    const first = list.loadRepositories();
    await list.loadRepositories();
    expect(reviewApi.fetchReviewRepositories.mock.calls[0][0].signal.aborted).toBe(true);
    oldRepositories.resolve(["stale/repo"]);
    await first;
    expect(list.repositories.value).toEqual(["current/repo"]);
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

const summaryFixture = { total: 26, highRisk: 2, failed: 1, averageDurationSeconds: 65 };

const resetReviewApi = () => {
  for (const request of Object.values(reviewApi)) request.mockReset();
};

const mountList = () => {
  let list!: ReturnType<typeof useReviewTasksList>;
  const app = createApp(defineComponent({ setup() {
    list = useReviewTasksList();
    return () => null;
  } }));
  app.mount(document.createElement("div"));
  mountedApps.add(app);
  return { list, app };
};

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
};

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
