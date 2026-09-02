import { ref, type Ref } from "vue";
import { fetchReviewAttemptComparison, fetchReviewExecutionAttempts } from "@/api/reviews";
import type { ReviewAttemptComparison, ReviewExecutionAttempt, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const PAGE_SIZE = 8;

type UseReviewDetailAttemptComparisonOptions = {
  selectedTask: Ref<ReviewTaskDetail | null>;
  isArchivedTask: Readonly<Ref<boolean>>;
};

/** Loads attempt history and the current attempt comparison for the detail page. */
export const useReviewDetailAttemptComparison = ({
  selectedTask,
  isArchivedTask
}: UseReviewDetailAttemptComparisonOptions) => {
  const attempts = ref<ReviewExecutionAttempt[]>([]);
  const comparison = ref<ReviewAttemptComparison | null>(null);
  const loading = ref(false);
  const error = ref("");
  const page = ref(1);
  let requestSequence = 0;

  const reset = () => {
    requestSequence += 1;
    attempts.value = [];
    comparison.value = null;
    loading.value = false;
    error.value = "";
    page.value = 1;
  };

  const load = async (requestedPage = page.value) => {
    const task = selectedTask.value;
    if (!task || isArchivedTask.value) {
      reset();
      return;
    }
    const taskId = task.id;
    const sequence = ++requestSequence;
    loading.value = true;
    error.value = "";
    try {
      const loadedAttempts = await fetchReviewExecutionAttempts(taskId);
      if (sequence !== requestSequence || selectedTask.value?.id !== taskId) {
        return;
      }
      attempts.value = loadedAttempts;
      const candidate = candidateAttempt(loadedAttempts);
      if (!candidate || !successful(candidate)) {
        comparison.value = null;
        page.value = 1;
        return;
      }
      const result = await fetchReviewAttemptComparison(taskId, candidate.id, {
        page: requestedPage,
        pageSize: PAGE_SIZE
      });
      if (sequence !== requestSequence || selectedTask.value?.id !== taskId) {
        return;
      }
      comparison.value = result;
      page.value = requestedPage;
    } catch (loadError) {
      if (sequence === requestSequence && selectedTask.value?.id === taskId) {
        attempts.value = [];
        comparison.value = null;
        error.value = getErrorMessage(loadError, "跨次审查差异加载失败");
      }
    } finally {
      if (sequence === requestSequence) {
        loading.value = false;
      }
    }
  };

  const candidateAttempt = (values: ReviewExecutionAttempt[]) =>
    values.find((attempt) => attempt.current) ?? values[0];

  const successful = (attempt: ReviewExecutionAttempt) =>
    attempt.status.toUpperCase() === "COMPLETED" || attempt.status.toUpperCase() === "PARTIAL";

  return {
    attempts,
    comparison,
    error,
    loading,
    page,
    pageSize: PAGE_SIZE,
    load,
    reset
  };
};
