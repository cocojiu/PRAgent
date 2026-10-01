import { computed, getCurrentScope, onScopeDispose, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchGithubCommentPreview, fetchGithubCommentPublicationBatches, publishGithubComments } from "@/api/reviews";
import type { GithubCommentPreview, GithubCommentPublicationBatch, GithubCommentPublicationHistory, GithubCommentPublish } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { useGithubCommentHistoryItems } from "./useGithubCommentHistoryItems";

const GITHUB_COMMENT_PREVIEW_PAGE_SIZE = 10;
const GITHUB_COMMENT_HISTORY_PAGE_SIZE = 5;
const GITHUB_COMMENT_PUBLISH_POLL_INTERVAL_MS = 3000;
const GITHUB_COMMENT_PUBLISH_MAX_POLLS = 120;
const GITHUB_COMMENT_PUBLISH_TERMINAL_STATUSES = new Set([
  "completed",
  "partial_failed",
  "failed",
  "skipped",
  "empty"
]);

type GithubCommentPreviewLoadOptions = {
  page?: number;
  commentableOnly?: boolean;
};

type GithubCommentHistoryLoadOptions = {
  page?: number;
  status?: string;
};

export const useReviewDetailGithubComments = () => {
  const previewError = ref("");
  const historyError = ref("");
  const previewLoading = ref(false);
  const historyLoading = ref(false);
  const publishingComments = ref(false);
  const previewPage = ref(1);
  const previewPageSize = GITHUB_COMMENT_PREVIEW_PAGE_SIZE;
  const previewCommentableOnly = ref(false);
  const historyPage = ref(1);
  const historyPageSize = GITHUB_COMMENT_HISTORY_PAGE_SIZE;
  const historyStatus = ref("");
  const githubCommentPreview = ref<GithubCommentPreview | null>(null);
  const githubCommentPublicationHistory = ref<GithubCommentPublicationHistory | null>(null);
  const githubCommentPublishResult = ref<GithubCommentPublish | null>(null);
  let publishPollTimer: number | null = null;
  let publishPollAttempts = 0;
  let activePublishBatchId: number | null = null;
  let historyController: AbortController | null = null;
  let historyTaskId: number | null = null;
  let disposed = false;
  let stateVersion = 0;
  const { historyItems, retainHistoryItems, clearHistoryItems, toggleHistoryBatch, changeHistoryItemsPage } = useGithubCommentHistoryItems();

  const publishedCommentCount = computed(() =>
    githubCommentPreview.value?.publishedCount
      ?? githubCommentPreview.value?.items.filter((item) => item.published).length
      ?? 0
  );
  const publicationHistoryBatches = computed<GithubCommentPublicationBatch[]>(() => githubCommentPublicationHistory.value?.batches ?? []);
  const publicationHistoryTotal = computed(() => githubCommentPublicationHistory.value?.total ?? 0);
  const writebackCheck = computed(() => githubCommentPreview.value?.writebackCheck);

  const batchToPublishResult = (taskId: number, batch: GithubCommentPublicationBatch): GithubCommentPublish => ({
    taskId,
    batchId: batch.batchId,
    status: batch.status,
    totalFindings: batch.totalFindings,
    attemptedCount: batch.attemptedCount,
    succeededCount: batch.succeededCount,
    failedCount: batch.failedCount,
    skippedCount: batch.skippedCount,
    nextRetryAt: batch.nextRetryAt,
    lastError: batch.lastError,
    items: batch.items
  });

  const isTerminalPublishStatus = (status?: string) =>
    Boolean(status && GITHUB_COMMENT_PUBLISH_TERMINAL_STATUSES.has(status));

  const loadGithubCommentPreview = async (id: number, options: GithubCommentPreviewLoadOptions = {}) => {
    previewError.value = "";
    previewLoading.value = true;
    const page = options.page ?? previewPage.value;
    const commentableOnly = options.commentableOnly ?? previewCommentableOnly.value;
    try {
      githubCommentPreview.value = await fetchGithubCommentPreview(id, {
        page,
        pageSize: previewPageSize,
        commentableOnly
      });
      previewPage.value = githubCommentPreview.value.page;
      previewCommentableOnly.value = githubCommentPreview.value.commentableOnly;
    } catch (error) {
      githubCommentPreview.value = null;
      previewError.value = getErrorMessage(error, "请求失败");
    } finally {
      previewLoading.value = false;
    }
  };

  const readPublicationBatches = async (id: number, options: GithubCommentHistoryLoadOptions, showLoading: boolean) => {
    if (disposed) return null;
    if (historyTaskId !== null && historyTaskId !== id) {
      clearGithubCommentPreviewAndHistory();
    }
    historyTaskId = id;
    historyController?.abort();
    const controller = new AbortController();
    historyController = controller;
    const current = () => !disposed && historyTaskId === id && historyController === controller && !controller.signal.aborted;
    historyError.value = "";
    historyLoading.value = showLoading;
    const page = options.page ?? historyPage.value;
    const status = options.status ?? historyStatus.value;
    try {
      const history = await fetchGithubCommentPublicationBatches(id, {
        page,
        pageSize: historyPageSize,
        status: status || undefined
      }, { signal: controller.signal });
      if (!current()) return null;
      if (history.taskId !== id) throw new Error("评论历史响应不符合当前任务");
      githubCommentPublicationHistory.value = history;
      historyPage.value = history.page;
      historyStatus.value = history.status ?? "";
      retainHistoryItems(id, history.batches.map(batch => batch.batchId));
      return history;
    } catch (error) {
      if (current()) historyError.value = getErrorMessage(error, "请求失败");
      return null;
    } finally {
      if (current()) historyLoading.value = false;
    }
  };
  const loadGithubCommentPublicationHistory = async (id: number, options: GithubCommentHistoryLoadOptions = {}) => {
    await readPublicationBatches(id, options, true);
  };

  const stopGithubCommentPublishPolling = () => {
    if (publishPollTimer !== null) {
      window.clearTimeout(publishPollTimer);
      publishPollTimer = null;
    }
    publishPollAttempts = 0;
    activePublishBatchId = null;
    historyController?.abort();
    historyController = null;
    historyLoading.value = false;
  };

  const scheduleGithubCommentPublishPoll = (
    id: number,
    batchId: number,
    afterComplete?: () => Promise<void>
  ) => {
    publishPollTimer = window.setTimeout(() => {
      void pollGithubCommentPublishBatch(id, batchId, afterComplete);
    }, GITHUB_COMMENT_PUBLISH_POLL_INTERVAL_MS);
  };

  const pollGithubCommentPublishBatch = async (
    id: number,
    batchId: number,
    afterComplete?: () => Promise<void>
  ) => {
    if (disposed || activePublishBatchId !== batchId) {
      return;
    }
    publishPollTimer = null;
    publishPollAttempts += 1;
    const history = await readPublicationBatches(id, { page: 1, status: "" }, false);
    if (disposed || activePublishBatchId !== batchId || historyTaskId !== id) return;
    if (history) {
      const activeBatch = history.batches.find((batch) => batch.batchId === batchId);
      if (activeBatch) {
        githubCommentPublishResult.value = batchToPublishResult(id, activeBatch);
        if (isTerminalPublishStatus(activeBatch.status)) {
          stopGithubCommentPublishPolling();
          if (activeBatch.failedCount > 0) {
            ElMessage.warning(`GitHub 评论回写完成：成功 ${activeBatch.succeededCount} 条，失败 ${activeBatch.failedCount} 条`);
          } else {
            ElMessage.success(`GitHub 评论回写完成：成功 ${activeBatch.succeededCount} 条`);
          }
          await afterComplete?.();
          return;
        }
      }
    }

    if (publishPollAttempts >= GITHUB_COMMENT_PUBLISH_MAX_POLLS) {
      stopGithubCommentPublishPolling();
      ElMessage.warning("GitHub 评论回写仍在后台执行，请稍后刷新历史查看结果");
      return;
    }
    scheduleGithubCommentPublishPoll(id, batchId, afterComplete);
  };

  const startGithubCommentPublishPolling = (
    id: number,
    batchId: number,
    afterComplete?: () => Promise<void>
  ) => {
    stopGithubCommentPublishPolling();
    activePublishBatchId = batchId;
    scheduleGithubCommentPublishPoll(id, batchId, afterComplete);
  };

  const clearGithubCommentPreviewAndHistory = () => {
    stateVersion += 1;
    stopGithubCommentPublishPolling();
    publishingComments.value = false;
    clearHistoryItems();
    historyTaskId = null;
    previewError.value = "";
    previewPage.value = 1;
    previewCommentableOnly.value = false;
    githubCommentPreview.value = null;
    historyError.value = "";
    historyPage.value = 1;
    historyStatus.value = "";
    githubCommentPublicationHistory.value = null;
  };

  const clearGithubCommentState = () => {
    clearGithubCommentPreviewAndHistory();
    githubCommentPublishResult.value = null;
  };

  const resetGithubCommentPublishResult = () => {
    stopGithubCommentPublishPolling();
    githubCommentPublishResult.value = null;
  };

  const publishGithubCommentsForTask = async (id: number, afterPublish?: () => Promise<void>) => {
    const version = stateVersion;
    if (disposed) return;
    publishingComments.value = true;
    try {
      const published = await publishGithubComments(id);
      if (disposed || version !== stateVersion) return;
      githubCommentPublishResult.value = published;
      const result = githubCommentPublishResult.value;
      if (result.status === "queued") {
        ElMessage.success(`GitHub 评论回写已加入队列${result.batchId ? `（批次 #${result.batchId}）` : ""}`);
      } else if (result.failedCount > 0) {
        ElMessage.warning(`GitHub 评论回写完成：成功 ${result.succeededCount} 条，失败 ${result.failedCount} 条`);
      } else {
        ElMessage.success(`GitHub 评论回写成功：${result.succeededCount} 条`);
      }
      await afterPublish?.();
      if (disposed || version !== stateVersion) return;
      if (result.status === "queued" && result.batchId) {
        startGithubCommentPublishPolling(id, result.batchId, afterPublish);
      }
    } catch (error) {
      if (!disposed && version === stateVersion) ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      if (!disposed && version === stateVersion) publishingComments.value = false;
    }
  };

  if (getCurrentScope()) onScopeDispose(() => {
    disposed = true;
    clearGithubCommentState();
  });

  return {
    githubCommentPreview,
    githubCommentPublicationHistory,
    githubCommentPublishResult,
    historyError,
    historyPage,
    historyPageSize,
    historyStatus,
    historyLoading,
    historyItems,
    toggleHistoryBatch,
    changeHistoryItemsPage,
    publicationHistoryTotal,
    previewError,
    previewCommentableOnly,
    previewPage,
    previewPageSize,
    previewLoading,
    publicationHistoryBatches,
    publishedCommentCount,
    publishingComments,
    writebackCheck,
    clearGithubCommentPreviewAndHistory,
    clearGithubCommentState,
    loadGithubCommentPreview,
    loadGithubCommentPublicationHistory,
    publishGithubCommentsForTask,
    resetGithubCommentPublishResult,
    stopGithubCommentPublishPolling
  };
};
