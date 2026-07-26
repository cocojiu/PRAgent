import { computed, onUnmounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewListSummary, fetchReviewRepositories, fetchReviews } from "@/api/reviews";
import type { MetricGridItem } from "@/components/MetricGrid.vue";
import type { ReviewStatus, ReviewTask, ReviewTaskListSummary, ReviewTaskTriggerSource, RiskLevel } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60);
  const restSeconds = seconds % 60;
  return `${minutes} 分 ${restSeconds} 秒`;
};

type ReviewTaskCursor = {
  cursorCreatedAt: string;
  cursorId: number;
};

export const useReviewTasksList = () => {
  const loading = ref(false);
  const errorMessage = ref("");
  const reviewTasks = ref<ReviewTask[]>([]);
  const allRepositories = ref<string[]>([]);
  const taskSummary = ref<ReviewTaskListSummary | null>(null);
  const totalTasks = ref(0);
  const repoFilter = ref("");
  const statusFilter = ref<ReviewStatus | "">("");
  const riskFilter = ref<RiskLevel | "">("");
  const sourceFilter = ref<ReviewTaskTriggerSource | "">("");
  const keyword = ref("");
  const currentPage = ref(1);
  const pageSize = ref(8);
  const pageCursors = new Map<number, ReviewTaskCursor>();
  let filterDebounceTimer: ReturnType<typeof setTimeout> | undefined;
  let taskRequestSeq = 0;
  let summaryRequestSeq = 0;

  const repositories = computed(() => allRepositories.value);

  const taskSummaryMetrics = computed<MetricGridItem[]>(() => {
    const summary = taskSummary.value;
    const total = summary?.total ?? 0;
    const highRiskCount = summary?.highRisk ?? 0;
    const failedCount = summary?.failed ?? 0;
    const avgSeconds = summary?.averageDurationSeconds ?? 0;

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
    const requestSeq = ++taskRequestSeq;
    loading.value = true;
    errorMessage.value = "";
    const cursor = pageCursors.get(currentPage.value);
    try {
      const page = await fetchReviews({
        page: currentPage.value,
        pageSize: pageSize.value,
        repository: repoFilter.value,
        status: statusFilter.value,
        riskLevel: riskFilter.value,
        triggerSource: sourceFilter.value,
        keyword: keyword.value.trim(),
        cursorCreatedAt: cursor?.cursorCreatedAt,
        cursorId: cursor?.cursorId,
        totalHint: cursor ? totalTasks.value : undefined
      });
      if (requestSeq !== taskRequestSeq) {
        return;
      }
      reviewTasks.value = page.items;
      totalTasks.value = page.total;
      rememberNextPageCursor(currentPage.value, page.items);
    } catch (error) {
      if (requestSeq !== taskRequestSeq) {
        return;
      }
      errorMessage.value = getErrorMessage(error, "审查任务加载失败");
      reviewTasks.value = [];
      totalTasks.value = 0;
    } finally {
      if (requestSeq === taskRequestSeq) {
        loading.value = false;
      }
    }
  };

  const loadSummary = async () => {
    const requestSeq = ++summaryRequestSeq;
    try {
      const summary = await fetchReviewListSummary({
        repository: repoFilter.value,
        status: statusFilter.value,
        riskLevel: riskFilter.value,
        triggerSource: sourceFilter.value,
        keyword: keyword.value.trim()
      });
      if (requestSeq !== summaryRequestSeq) {
        return;
      }
      taskSummary.value = summary;
    } catch {
      if (requestSeq === summaryRequestSeq) {
        taskSummary.value = null;
      }
    }
  };

  const loadRepositories = async () => {
    try {
      allRepositories.value = await fetchReviewRepositories();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "请求失败"));
    }
  };

  const resetPage = () => {
    currentPage.value = 1;
  };

  const clearPageCursors = () => {
    pageCursors.clear();
  };

  const rememberNextPageCursor = (page: number, tasks: ReviewTask[]) => {
    const lastTask = tasks.at(-1);
    if (!lastTask?.createdAt || !lastTask.id) {
      pageCursors.delete(page + 1);
      return;
    }
    pageCursors.set(page + 1, {
      cursorCreatedAt: lastTask.createdAt,
      cursorId: lastTask.id
    });
  };

  const scheduleFilterLoad = () => {
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
