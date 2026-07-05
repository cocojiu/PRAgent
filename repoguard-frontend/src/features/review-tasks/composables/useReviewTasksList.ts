import { computed, onUnmounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewRepositories, fetchReviews } from "@/api/reviews";
import type { MetricGridItem } from "@/components/MetricGrid.vue";
import type { ReviewStatus, ReviewTask, ReviewTaskTriggerSource, RiskLevel } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const parseDurationSeconds = (duration: string) => {
  const [minutes = 0, seconds = 0] = duration.match(/\d+/g)?.map(Number) ?? [];
  return minutes * 60 + seconds;
};

const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60);
  const restSeconds = seconds % 60;
  return `${minutes} 分 ${restSeconds} 秒`;
};

export const useReviewTasksList = () => {
  const loading = ref(false);
  const errorMessage = ref("");
  const reviewTasks = ref<ReviewTask[]>([]);
  const allRepositories = ref<string[]>([]);
  const totalTasks = ref(0);
  const repoFilter = ref("");
  const statusFilter = ref<ReviewStatus | "">("");
  const riskFilter = ref<RiskLevel | "">("");
  const sourceFilter = ref<ReviewTaskTriggerSource | "">("");
  const keyword = ref("");
  const currentPage = ref(1);
  const pageSize = ref(8);
  let filterDebounceTimer: ReturnType<typeof setTimeout> | undefined;
  let taskRequestSeq = 0;

  const repositories = computed(() => allRepositories.value);

  const taskSummaryMetrics = computed<MetricGridItem[]>(() => {
    const tasks = reviewTasks.value;
    const total = tasks.length;
    const highRiskCount = tasks.filter((task) => task.riskLevel === "high" || task.riskLevel === "critical").length;
    const failedCount = tasks.filter((task) => task.status === "failed").length;
    const avgSeconds = total
      ? Math.round(tasks.reduce((sum, task) => sum + parseDurationSeconds(task.duration), 0) / total)
      : 0;

    return [
      { label: "本周审查", value: String(total), note: "当前筛选结果", noteClass: "trend", color: "blue" },
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
      { label: "平均耗时", value: formatDuration(avgSeconds), note: "按当前结果计算", noteClass: "trend", color: "green" }
    ];
  });

  const loadTasks = async () => {
    const requestSeq = ++taskRequestSeq;
    loading.value = true;
    errorMessage.value = "";
    try {
      const page = await fetchReviews({
        page: currentPage.value,
        pageSize: pageSize.value,
        repository: repoFilter.value,
        status: statusFilter.value,
        riskLevel: riskFilter.value,
        triggerSource: sourceFilter.value,
        keyword: keyword.value.trim()
      });
      if (requestSeq !== taskRequestSeq) {
        return;
      }
      reviewTasks.value = page.items;
      totalTasks.value = page.total;
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

  const scheduleFilterLoad = () => {
    if (filterDebounceTimer) {
      clearTimeout(filterDebounceTimer);
    }
    filterDebounceTimer = setTimeout(() => {
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
    void loadTasks();
  };

  const initializeReviewTasksList = () => {
    void loadTasks();
    void loadRepositories();
  };

  watch([repoFilter, statusFilter, riskFilter, sourceFilter, keyword], scheduleFilterLoad);

  watch([currentPage, pageSize], () => {
    void loadTasks();
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
