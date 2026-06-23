import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewDetail, fetchReviewStatus } from "@/api/reviews";
import type { ReviewStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { applyReviewStatusSnapshot, normalizeReviewTaskDetail } from "../reviewDetailTaskMappers";

type LoadDetailOptions = { silent?: boolean; resetPublishResult?: boolean; force?: boolean };

type UseReviewDetailLoaderOptions = {
  clearGithubCommentPreviewAndHistory: () => void;
  getTaskId: () => number;
  isTerminalReviewStatus: (status?: ReviewStatus | string) => boolean;
  loadGithubCommentPreview: (id: number) => Promise<void>;
  loadGithubCommentPublicationHistory: (id: number) => Promise<void>;
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
  clearGithubCommentPreviewAndHistory,
  getTaskId,
  isTerminalReviewStatus,
  loadGithubCommentPreview,
  loadGithubCommentPublicationHistory,
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

  const loadTerminalGithubCommentData = async (id: number, status: ReviewStatus | string) => {
    if (isTerminalReviewStatus(status)) {
      await Promise.all([
        loadGithubCommentPreview(id),
        loadGithubCommentPublicationHistory(id)
      ]);
      return;
    }
    clearGithubCommentPreviewAndHistory();
  };

  const loadDetail = async (options: LoadDetailOptions = {}) => {
    const id = getTaskId();
    if (!Number.isFinite(id)) {
      ElMessage.error("审查任务 ID 无效");
      return;
    }

    if (options.silent && silentRefreshing.value && !options.force) {
      return;
    }

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
      selectedTask.value = task;
      pollErrorMessage.value = "";
      pollFailureCount.value = 0;
      lastRefreshedAt.value = formatRefreshTime();
      await loadTerminalGithubCommentData(id, task.status);
      syncPolling();
    } catch (error) {
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
      loading.value = false;
      silentRefreshing.value = false;
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

    silentRefreshing.value = true;
    try {
      const status = await fetchReviewStatus(id);
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
      silentRefreshing.value = false;
    }
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
    loadDetail,
    pollReviewStatus,
    refreshDetail
  };
};
