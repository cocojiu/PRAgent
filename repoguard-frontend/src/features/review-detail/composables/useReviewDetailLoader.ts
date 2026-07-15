import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewDetail, fetchReviewStatus } from "@/api/reviews";
import type { ReviewStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { applyReviewStatusSnapshot, normalizeReviewTaskDetail } from "../reviewDetailTaskMappers";

type LoadDetailOptions = { silent?: boolean; resetPublishResult?: boolean; force?: boolean };

type UseReviewDetailLoaderOptions = {
  afterDetailLoaded?: (task: ReviewTaskDetail) => void;
  clearGithubCommentPreviewAndHistory: () => void;
  getTaskId: () => number;
  isTerminalReviewStatus: (status?: ReviewStatus | string) => boolean;
  maxPollFailures: number;
  resetGithubCommentPublishResult: () => void;
  stopPolling: () => void;
  syncPolling: () => void;
};

const formatRefreshTime = () =>
  new Intl.DateTimeFormat("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date());

export const useReviewDetailLoader = ({
  afterDetailLoaded,
  clearGithubCommentPreviewAndHistory,
  getTaskId,
  isTerminalReviewStatus,
  maxPollFailures,
  resetGithubCommentPublishResult,
  stopPolling,
  syncPolling
}: UseReviewDetailLoaderOptions) => {
  const loading = ref(false);
  const silentRefreshing = ref(false);
  const errorMessage = ref("");
  const pollErrorMessage = ref("");
  const pollFailureCount = ref(0);
  const lastRefreshedAt = ref("");
  const selectedTask = ref<ReviewTaskDetail | null>(null);
  let requestSequence = 0;

  const beginRequest = () => ++requestSequence;
  const isLatestRequest = (sequence: number) => sequence === requestSequence;

  const clearNonTerminalGithubCommentData = (status: ReviewStatus | string) => {
    if (isTerminalReviewStatus(status)) {
      return;
    }
    clearGithubCommentPreviewAndHistory();
  };

  const loadDetail = async (options: LoadDetailOptions = {}) => {
    const id = getTaskId();
    if (!Number.isFinite(id)) {
      requestSequence += 1;
      ElMessage.error("审查任务 ID 无效");
      return;
    }

    if (options.silent && silentRefreshing.value && !options.force) {
      return;
    }

    const sequence = beginRequest();

    if (options.silent) {
      silentRefreshing.value = true;
    } else {
      loading.value = true;
    }
    errorMessage.value = "";
    if (options.resetPublishResult ?? true) {
      resetGithubCommentPublishResult();
    }
    try {
      const task = normalizeReviewTaskDetail(await fetchReviewDetail(id));
      if (!isLatestRequest(sequence)) {
        return;
      }
      selectedTask.value = task;
      afterDetailLoaded?.(task);
      pollErrorMessage.value = "";
      pollFailureCount.value = 0;
      lastRefreshedAt.value = formatRefreshTime();
      clearNonTerminalGithubCommentData(task.status);
      syncPolling();
    } catch (error) {
      if (!isLatestRequest(sequence)) {
        return;
      }
      if (!options.silent) {
        selectedTask.value = null;
      }
      errorMessage.value = getErrorMessage(error, "请求失败");
      if (!options.silent) {
        ElMessage.error(errorMessage.value);
      } else {
        pollFailureCount.value += 1;
        if (pollFailureCount.value >= maxPollFailures) {
          stopPolling();
          pollErrorMessage.value = `自动刷新连续失败 ${maxPollFailures} 次，已暂停。请手动刷新。`;
        } else {
          pollErrorMessage.value = `自动刷新失败：${errorMessage.value}`;
          syncPolling();
        }
      }
    } finally {
      if (isLatestRequest(sequence)) {
        loading.value = false;
        silentRefreshing.value = false;
      }
    }
  };

  const pollReviewStatus = async () => {
    const id = getTaskId();
    if (!Number.isFinite(id)) {
      return;
    }
    if (silentRefreshing.value) {
      return;
    }

    const sequence = beginRequest();
    silentRefreshing.value = true;
    try {
      const status = await fetchReviewStatus(id);
      if (!isLatestRequest(sequence)) {
        return;
      }
      if (selectedTask.value) {
        selectedTask.value = applyReviewStatusSnapshot(selectedTask.value, status);
      }
      pollErrorMessage.value = "";
      pollFailureCount.value = 0;
      lastRefreshedAt.value = formatRefreshTime();
      if (isTerminalReviewStatus(status.status as ReviewStatus)) {
        await loadDetail({ silent: true, resetPublishResult: false, force: true });
        return;
      }
      syncPolling();
    } catch (error) {
      if (!isLatestRequest(sequence)) {
        return;
      }
      pollFailureCount.value += 1;
      const message = getErrorMessage(error, "请求失败");
      if (pollFailureCount.value >= maxPollFailures) {
        stopPolling();
        pollErrorMessage.value = `Automatic refresh failed ${maxPollFailures} times and has paused. Please refresh manually.`;
      } else {
        pollErrorMessage.value = `Automatic refresh failed: ${message}`;
        syncPolling();
      }
    } finally {
      if (isLatestRequest(sequence)) {
        silentRefreshing.value = false;
      }
    }
  };

  const cancelPendingRequests = () => {
    requestSequence += 1;
    loading.value = false;
    silentRefreshing.value = false;
  };

  const refreshDetail = () => {
    pollFailureCount.value = 0;
    pollErrorMessage.value = "";
    return loadDetail({ silent: true, resetPublishResult: false });
  };

  return {
    errorMessage,
    lastRefreshedAt,
    loading,
    pollErrorMessage,
    pollFailureCount,
    selectedTask,
    silentRefreshing,
    cancelPendingRequests,
    loadDetail,
    pollReviewStatus,
    refreshDetail
  };
};
