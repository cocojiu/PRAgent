import { beforeEach, describe, expect, it, vi } from "vitest";
import { computed, ref } from "vue";
import { fetchReviewAttemptComparison, fetchReviewExecutionAttempts } from "@/api/reviews";
import type { ReviewAttemptComparison, ReviewExecutionAttempt, ReviewTaskDetail } from "@/types";
import { useReviewDetailAttemptComparison } from "./useReviewDetailAttemptComparison";

vi.mock("@/api/reviews", () => ({
  fetchReviewAttemptComparison: vi.fn(),
  fetchReviewExecutionAttempts: vi.fn()
}));

describe("useReviewDetailAttemptComparison", () => {
  const loadAttempts = vi.mocked(fetchReviewExecutionAttempts);
  const loadComparison = vi.mocked(fetchReviewAttemptComparison);

  beforeEach(() => vi.clearAllMocks());

  it("loads current successful attempt and bounded comparison page", async () => {
    const attempts = [attempt(11, true, "COMPLETED"), attempt(10, false, "COMPLETED")];
    loadAttempts.mockResolvedValue(attempts);
    loadComparison.mockResolvedValue(comparison(11));
    const selectedTask = ref(task(7));
    const state = useReviewDetailAttemptComparison({
      selectedTask,
      isArchivedTask: computed(() => false)
    });

    await state.load();

    expect(loadAttempts).toHaveBeenCalledWith(7);
    expect(loadComparison).toHaveBeenCalledWith(7, 11, { page: 1, pageSize: 8 });
    expect(state.attempts.value).toHaveLength(2);
    expect(state.comparison.value?.candidateAttemptId).toBe(11);
  });

  it("supports comparison paging and skips unfinished attempts", async () => {
    loadAttempts.mockResolvedValue([attempt(12, true, "RUNNING")]);
    const selectedTask = ref(task(8));
    const state = useReviewDetailAttemptComparison({
      selectedTask,
      isArchivedTask: computed(() => false)
    });

    await state.load(2);

    expect(loadComparison).not.toHaveBeenCalled();
    expect(state.comparison.value).toBeNull();
    expect(state.page.value).toBe(1);
  });

  it("clears state for archived tasks and surfaces request errors", async () => {
    const selectedTask = ref(task(9));
    const archived = ref(true);
    const state = useReviewDetailAttemptComparison({ selectedTask, isArchivedTask: archived });

    await state.load();
    expect(loadAttempts).not.toHaveBeenCalled();
    expect(state.attempts.value).toEqual([]);

    archived.value = false;
    loadAttempts.mockRejectedValueOnce(new Error("历史接口不可用"));
    await state.load();
    expect(state.error.value).toBe("历史接口不可用");
    expect(state.loading.value).toBe(false);
  });
});

const task = (id: number) => ({ id } as ReviewTaskDetail);

const attempt = (id: number, current: boolean, status: string): ReviewExecutionAttempt => ({
  id,
  taskId: 7,
  attemptNo: id,
  generation: id,
  status,
  current
});

const comparison = (candidateAttemptId: number): ReviewAttemptComparison => ({
  taskId: 7,
  candidateAttemptId,
  comparable: true,
  comparabilityReason: "NO_PREVIOUS_SUCCESSFUL_ATTEMPT",
  summary: { newCount: 0, persistingCount: 0, resolvedCount: 0, regressedCount: 0, unmatchedCount: 0, total: 0 },
  findings: { items: [], total: 0, nextCursor: null, hasMore: false }
});
