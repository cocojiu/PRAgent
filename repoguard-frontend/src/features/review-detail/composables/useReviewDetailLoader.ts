import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewDetail, fetchReviewStatus } from "@/api/reviews";
import type { ReviewStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { commonUserMessages, reviewDetailMessages } from "@/utils/userMessages";
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
  let activeRequest: { controller: AbortController; sequence: number } | undefined;
  let requestSequence = 0;

  const beginRequest = () => {
    activeRequest?.controller.abort();
    const request = {
      controller: new AbortController(),
      sequence: ++requestSequence
    };
    activeRequest = request;
    return request;
  };
  const isLatestRequest = (sequence: number) => sequence === requestSequence;

  const completeRequest = (sequence: number) => {
    if (activeRequest?.sequence === sequence) {
      activeRequest = undefined;
    }
  };

  const clearNonTerminalGithubCommentData = (status: ReviewStatus | string) => {
    if (isTerminalReviewStatus(status) && String(status).toLowerCase() !== "superseded") {
      return;
    }
    clearGithubCommentPreviewAndHistory();
  };

  const loadDetail = async (options: LoadDetailOptions = {}) => {
    const id = getTaskId();
    if (!Number.isFinite(id)) {
      requestSequence += 1;
      ElMessage.error(reviewDetailMessages.invalidTaskId);
      return;
    }

    if (options.silent && silentRefreshing.value && !options.force) {
      return;
    }

    const request = beginRequest();
    const { sequence } = request;

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
      const task = normalizeReviewTaskDetail(await fetchReviewDetail(id, { signal: request.controller.signal }));
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
      errorMessage.value = getErrorMessage(error, commonUserMessages.requestFailed);
      if (options.silent) {
        pollFailureCount.value += 1;
        if (pollFailureCount.value >= maxPollFailures) {
          stopPolling();
          pollErrorMessage.value = reviewDetailMessages.pollingPaused(maxPollFailures);
        } else {
          pollErrorMessage.value = reviewDetailMessages.pollFailed(errorMessage.value);
          syncPolling();
        }
      }
    } finally {
      if (isLatestRequest(sequence)) {
        loading.value = false;
        silentRefreshing.value = false;
      }
      completeRequest(sequence);
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

    const request = beginRequest();
    const { sequence } = request;
    silentRefreshing.value = true;
    try {
      const status = await fetchReviewStatus(id, { signal: request.controller.signal });
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
      const message = getErrorMessage(error, commonUserMessages.requestFailed);
      if (pollFailureCount.value >= maxPollFailures) {
        stopPolling();
        pollErrorMessage.value = reviewDetailMessages.pollingPaused(maxPollFailures);
      } else {
        pollErrorMessage.value = reviewDetailMessages.pollFailed(message);
        syncPolling();
      }
    } finally {
      if (isLatestRequest(sequence)) {
        silentRefreshing.value = false;
      }
      completeRequest(sequence);
    }
  };

  const cancelPendingRequests = () => {
    activeRequest?.controller.abort();
    activeRequest = undefined;
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
