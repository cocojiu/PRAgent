import { computed, nextTick, onScopeDispose, onWatcherCleanup, watch } from "vue";
import { activeTenant } from "@/stores/tenantContext";
import { useReviewDetailGithubComments } from "./useReviewDetailGithubComments";
import { useReviewDetailLoader } from "./useReviewDetailLoader";
import { useReviewDetailSectionLoaders } from "./useReviewDetailSectionLoaders";
import { useReviewDetailAttemptComparison } from "./useReviewDetailAttemptComparison";
import { useReviewDetailPolling } from "./useReviewDetailPolling";

const terminalStatuses = new Set(["completed", "failed", "superseded", "pending_human_review",
  "approved", "changes_requested", "rejected"]);
const isTerminalReviewStatus = (status?: string) => terminalStatuses.has(status ?? "");

/** Owns one task/tenant session. Domain actions keep their own authorization and latest-intent guards. */
export const useReviewDetailSession = ({ getTaskId }: { getTaskId: () => number }) => {
  const comments = useReviewDetailGithubComments();
  let resetSections = () => {};
  let loadComparison = () => {};
  let stopPolling = () => {};
  let syncPolling = () => {};
  const loader = useReviewDetailLoader({
    getTaskId, isTerminalReviewStatus, maxPollFailures: 3,
    afterDetailLoaded: () => { resetSections(); loadComparison(); },
    clearGithubCommentPreviewAndHistory: comments.clearGithubCommentPreviewAndHistory,
    resetGithubCommentPublishResult: comments.resetGithubCommentPublishResult,
    stopPolling: () => stopPolling(), syncPolling: () => syncPolling()
  });
  const { selectedTask, pollFailureCount } = loader;
  const isTerminalTask = computed(() => isTerminalReviewStatus(selectedTask.value?.status));
  const isArchivedTask = computed(() => selectedTask.value?.archived === true);
  const isSupersededTask = computed(() => selectedTask.value?.status === "superseded");
  const canLoadGithubComments = computed(() => isTerminalTask.value && !isSupersededTask.value && !isArchivedTask.value);
  const sections = useReviewDetailSectionLoaders({ selectedTask, isArchivedTask });
  const comparison = useReviewDetailAttemptComparison({ selectedTask, isArchivedTask });
  resetSections = sections.resetDetailSections;
  loadComparison = () => { void comparison.load(); };
  const shouldPollTask = computed(() => Boolean(selectedTask.value && !isTerminalTask.value));
  const currentPollIntervalMs = computed(() => Math.min(5000 * 2 ** pollFailureCount.value, 30000));
  const polling = useReviewDetailPolling({ getTaskId, currentPollIntervalMs, maxPollFailures: 3,
    pollFailureCount, pollReviewStatus: loader.pollReviewStatus, shouldPollTask });
  stopPolling = polling.stopPolling;
  syncPolling = polling.syncPolling;

  const cancelSession = () => {
    loader.cancelPendingRequests();
    polling.stopPolling();
    comments.clearGithubCommentState();
    comments.stopGithubCommentPublishPolling();
    sections.resetDetailSections();
    comparison.reset();
  };
  watch([getTaskId, activeTenant], async ([taskId]) => {
    let current = true;
    onWatcherCleanup(() => { current = false; });
    cancelSession();
    selectedTask.value = null;
    // Route params can disappear before the departing component is disposed.
    if (!Number.isFinite(taskId)) await nextTick();
    if (current) void loader.loadDetail();
  }, { immediate: true, flush: "sync" });
  onScopeDispose(() => { cancelSession(); polling.cleanupPolling(); });

  return {
    ...comments, ...loader, ...sections,
    isTerminalTask, isArchivedTask, isSupersededTask, canLoadGithubComments,
    progressConnectionState: polling.connectionState,
    currentPollIntervalSeconds: computed(() => currentPollIntervalMs.value / 1000),
    attemptComparisonAttempts: comparison.attempts,
    attemptComparisonResult: comparison.comparison,
    attemptComparisonError: comparison.error,
    attemptComparisonLoading: comparison.loading,
    attemptComparisonPage: comparison.page,
    attemptComparisonPageSize: comparison.pageSize,
    loadAttemptComparison: comparison.load,
    refreshAfterRetry: () => loader.loadDetail({ silent: true, resetPublishResult: true, force: true }),
    refreshAfterHumanReview: () => loader.refreshStatusSummary()
  };
};
