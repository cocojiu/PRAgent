import { computed, onUnmounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewListSummary, fetchReviewRepositories, fetchReviews } from "@/api/reviews";
import type { MetricGridItem } from "@/components/MetricGrid.vue";
import type { ReviewStatus, ReviewTask, ReviewTaskListSummary, ReviewTaskTriggerSource, RiskLevel } from "@/types";
import { getErrorMessage, RequestError } from "@/utils/errors";

const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60);
  const restSeconds = seconds % 60;
  return `${minutes} 分 ${restSeconds} 秒`;
};

export const useReviewTasksList = (initialStatus: ReviewStatus | "" = "") => {
  const loading = ref(false);
  const errorMessage = ref("");
  const reviewTasks = ref<ReviewTask[]>([]);
  const allRepositories = ref<string[]>([]);
  const taskSummary = ref<ReviewTaskListSummary | null>(null);
  const totalTasks = ref(0);
  const repoFilter = ref("");
  const statusFilter = ref<ReviewStatus | "">(initialStatus);
  const riskFilter = ref<RiskLevel | "">("");
  const sourceFilter = ref<ReviewTaskTriggerSource | "">("");
  const keyword = ref("");
  const currentPage = ref(1);
  const pageSize = ref(8);
  const pageCursors = new Map<number, string>();
  let filterDebounceTimer: ReturnType<typeof setTimeout> | undefined;
  let taskRequestSeq = 0;
  let summaryRequestSeq = 0;
  let repositoryRequestSeq = 0;
  let taskController: AbortController | undefined;
  let summaryController: AbortController | undefined;
  let repositoryController: AbortController | undefined;
  let disposed = false;

  const repositories = computed(() => allRepositories.value);

  const taskSummaryMetrics = computed<MetricGridItem[]>(() => {
    const summary = taskSummary.value;
    if (!summary) {
      return [
        { label: "任务总数", value: "—", note: "数据暂不可用", noteClass: "trend", color: "blue" },
        { label: "高风险 PR", value: "—", note: "数据暂不可用", noteClass: "trend danger", color: "red" },
        { label: "失败任务", value: "—", note: "数据暂不可用", noteClass: "trend danger", color: "orange" },
        { label: "平均耗时", value: "—", note: "数据暂不可用", noteClass: "trend", color: "green" }
      ];
    }
    const total = summary.total;
    const highRiskCount = summary.highRisk;
    const failedCount = summary.failed;
    const avgSeconds = summary.averageDurationSeconds;

    return [
      { label: "任务总数", value: String(total), note: "当前筛选结果", noteClass: "trend", color: "blue" },
      {
        label: "高风险 PR",
        value: String(highRiskCount),
        note: `${total ? Math.round((highRiskCount / total) * 100) : 0}% 占比`,
        noteClass: "trend danger",
        color: "red"
      },
      {
        label: "失败任务",
        value: String(failedCount),
        note: `${total ? Math.round((failedCount / total) * 100) : 0}% 占比`,
        noteClass: "trend danger",
        color: "orange"
      },
      { label: "平均耗时", value: formatDuration(avgSeconds), note: "按当前筛选计算", noteClass: "trend", color: "green" }
    ];
  });

  const loadTasks = async () => {
    if (disposed) return;
    const requestSeq = ++taskRequestSeq;
    taskController?.abort();
    const controller = new AbortController();
    taskController = controller;
    loading.value = true;
    errorMessage.value = "";
    // Human decisions remove tasks from this live queue. Request a fresh server count
    // instead of reusing the total embedded in an earlier page's cursor.
    const cursor = statusFilter.value === "pending_human_review" ? undefined : pageCursors.get(currentPage.value);
    try {
      const page = await fetchReviews({
        page: currentPage.value,
        pageSize: pageSize.value,
        repository: repoFilter.value,
        status: statusFilter.value,
        riskLevel: riskFilter.value,
        triggerSource: sourceFilter.value,
        keyword: keyword.value.trim(),
        cursor
      }, { signal: controller.signal });
      if (disposed || requestSeq !== taskRequestSeq || controller.signal.aborted) {
        return;
      }
      const lastPage = Math.max(1, Math.ceil(page.total / pageSize.value));
      if (statusFilter.value === "pending_human_review" && currentPage.value > lastPage) {
        currentPage.value = lastPage;
        return;
      }
      reviewTasks.value = page.items;
      totalTasks.value = page.total;
      rememberNextPageCursor(currentPage.value, page.nextCursor, page.hasMore);
    } catch (error) {
      if (disposed || requestSeq !== taskRequestSeq || isCancelledRequest(error, controller.signal)) {
        return;
      }
      errorMessage.value = getErrorMessage(error, "审查任务加载失败");
      reviewTasks.value = [];
      totalTasks.value = 0;
    } finally {
      if (!disposed && requestSeq === taskRequestSeq) {
        loading.value = false;
        taskController = undefined;
      }
    }
  };

  const loadSummary = async () => {
    if (disposed) return;
    const requestSeq = ++summaryRequestSeq;
    summaryController?.abort();
    const controller = new AbortController();
    summaryController = controller;
    try {
      const summary = await fetchReviewListSummary({
        repository: repoFilter.value,
        status: statusFilter.value,
        riskLevel: riskFilter.value,
        triggerSource: sourceFilter.value,
        keyword: keyword.value.trim()
      }, { signal: controller.signal });
      if (disposed || requestSeq !== summaryRequestSeq || controller.signal.aborted) {
        return;
      }
      taskSummary.value = summary;
    } catch {
      // Keep the last successful summary. An unavailable value must never be presented as a real zero.
    } finally {
      if (requestSeq === summaryRequestSeq) summaryController = undefined;
    }
  };

  const loadRepositories = async () => {
    if (disposed) return;
    const requestSeq = ++repositoryRequestSeq;
    repositoryController?.abort();
    const controller = new AbortController();
    repositoryController = controller;
    try {
      const result = await fetchReviewRepositories({ signal: controller.signal });
      if (!disposed && requestSeq === repositoryRequestSeq && !controller.signal.aborted) {
        allRepositories.value = result;
      }
    } catch (error) {
      if (disposed || requestSeq !== repositoryRequestSeq || isCancelledRequest(error, controller.signal)) return;
      ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      if (requestSeq === repositoryRequestSeq) repositoryController = undefined;
    }
  };

  const resetPage = () => {
    currentPage.value = 1;
  };

  const clearPageCursors = () => {
    pageCursors.clear();
  };

  const rememberNextPageCursor = (page: number, nextCursor?: string | null, hasMore?: boolean) => {
    if (!hasMore || !nextCursor) {
      pageCursors.delete(page + 1);
      return;
    }
    pageCursors.set(page + 1, nextCursor);
  };

  const scheduleFilterLoad = () => {
    if (disposed) return;
    ++taskRequestSeq;
    ++summaryRequestSeq;
    taskController?.abort();
    summaryController?.abort();
    taskController = undefined;
    summaryController = undefined;
    errorMessage.value = "";
    reviewTasks.value = [];
    totalTasks.value = 0;
    taskSummary.value = null;
    loading.value = true;
    clearPageCursors();
    if (filterDebounceTimer) {
      clearTimeout(filterDebounceTimer);
    }
    filterDebounceTimer = setTimeout(() => {
      void loadSummary();
      if (currentPage.value === 1) {
        void loadTasks();
      } else {
        resetPage();
      }
    }, 350);
  };

  const refreshTasks = () => {
    if (disposed) return;
    if (filterDebounceTimer) {
      clearTimeout(filterDebounceTimer);
    }
    clearPageCursors();
    void loadTasks();
    void loadSummary();
  };

  const initializeReviewTasksList = () => {
    void loadTasks();
    void loadSummary();
    void loadRepositories();
  };

  watch([repoFilter, statusFilter, riskFilter, sourceFilter, keyword], scheduleFilterLoad);

  watch(currentPage, () => {
    void loadTasks();
  });

  watch(pageSize, () => {
    clearPageCursors();
    if (currentPage.value === 1) {
      void loadTasks();
    } else {
      resetPage();
    }
  });

  onUnmounted(() => {
    disposed = true;
    ++taskRequestSeq;
    ++summaryRequestSeq;
    ++repositoryRequestSeq;
    taskController?.abort();
    summaryController?.abort();
    repositoryController?.abort();
    if (filterDebounceTimer) {
      clearTimeout(filterDebounceTimer);
    }
  });

  return {
    currentPage,
    errorMessage,
    keyword,
    loading,
    pageSize,
    repoFilter,
    repositories,
    reviewTasks,
    riskFilter,
    sourceFilter,
    statusFilter,
    taskSummaryMetrics,
    totalTasks,
    initializeReviewTasksList,
    loadRepositories,
    loadTasks,
    refreshTasks
  };
};

const isCancelledRequest = (error: unknown, signal: AbortSignal) =>
  signal.aborted || (error instanceof RequestError && error.code === "REQUEST_ABORTED")
  || (error instanceof DOMException && error.name === "AbortError");
