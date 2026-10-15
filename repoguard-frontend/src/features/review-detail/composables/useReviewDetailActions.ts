import { computed } from "vue";
import { canManage } from "@/stores/authState";
import { humanReviewActionText, findingFeedbackPromptTitle } from "../reviewDetailDisplayMappers";
import { useReviewDetailRetry } from "./useReviewDetailRetry";
import { useReviewDetailHumanReviewDisplay } from "./useReviewDetailHumanReviewDisplay";
import { useReviewDetailHumanReview } from "./useReviewDetailHumanReview";
import { useReviewDetailFindingFeedback } from "./useReviewDetailFindingFeedback";
import { useReviewDetailGithubCommentPublishConfirm } from "./useReviewDetailGithubCommentPublishConfirm";
import type { useReviewDetailSession } from "./useReviewDetailSession";

/** Coordinates minimal refreshes after domain commands; command hooks own concurrency and permissions. */
export const useReviewDetailActions = (
  session: ReturnType<typeof useReviewDetailSession>, getTaskId: () => number
) => {
  const { selectedTask, isArchivedTask, isSupersededTask, isTerminalTask, canLoadGithubComments,
    githubCommentPreview, previewLoading, previewError, writebackCheck, publishingComments,
    loadGithubCommentPreview, loadGithubCommentPublicationHistory, clearGithubCommentState,
    publishGithubCommentsForTask, resetGithubCommentPublishResult, toggleHistoryBatch, changeHistoryItemsPage,
    pollFailureCount, pollErrorMessage, refreshAfterRetry, refreshAfterHumanReview } = session;
  const canPublishGithubComments = computed(() =>
    Boolean(
      canManage.value
        && !isArchivedTask.value
        && !isSupersededTask.value
        && !previewLoading.value
        && !previewError.value
        && githubCommentPreview.value?.taskId === getTaskId()
        && githubCommentPreview.value?.commentableCount
        && writebackCheck.value?.tokenConfigured !== false
        && isHumanReviewPublishAllowed.value
    )
  );


  const canManageHotTask = computed(() => canManage.value && !isArchivedTask.value);

  const canRetryTask = computed(() =>
    (selectedTask.value?.status === "failed" || selectedTask.value?.status === "superseded")
      && !isArchivedTask.value
  );
  const failureReason = computed(() => selectedTask.value?.failureReason ?? "");

  const { confirmRetryReview, retryingTask } = useReviewDetailRetry({
    canManage,
    getTaskId,
    canRetryTask,
    clearGithubCommentState,
    failureReason,
    refreshDetail: refreshAfterRetry,
    resetPollFailure: () => {
      pollFailureCount.value = 0;
      pollErrorMessage.value = "";
    },
    selectedTask
  });
  const {
    canSubmitHumanReview,
    humanReviewPublishBlockReason,
    humanReviewStatusClass,
    humanReviewStatusText,
    isHumanReviewPublishAllowed
  } = useReviewDetailHumanReviewDisplay({
    selectedTask
  });

  const { submittingHumanReview, submitHumanReviewDecision } = useReviewDetailHumanReview({
    canManage,
    getTaskId,
    canSubmitHumanReview,
    humanReviewActionText,
    refreshDetail: refreshAfterHumanReview,
    selectedTask
  });

  const refreshLoadedGithubCommentPreview = async (id: number) => {
    if (!githubCommentPreview.value) {
      return;
    }
    await loadGithubCommentPreview(id);
  };

  const loadGithubCommentPreviewPage = async (page: number) => {
    const id = getTaskId();
    if (!Number.isFinite(id) || !canLoadGithubComments.value || !githubCommentPreview.value) {
      return;
    }
    await loadGithubCommentPreview(id, { page });
  };

  const loadGithubCommentPreviewCommentableOnly = async (commentableOnly: boolean) => {
    const id = getTaskId();
    if (!Number.isFinite(id) || !canLoadGithubComments.value) {
      return;
    }
    await loadGithubCommentPreview(id, { page: 1, commentableOnly });
  };

  const loadGithubCommentPublicationHistoryPage = async (page: number) => {
    const id = getTaskId();
    if (!Number.isFinite(id) || !canLoadGithubComments.value) {
      return;
    }
    await loadGithubCommentPublicationHistory(id, { page });
  };

  const toggleGithubCommentHistoryBatch = async (batchId: number) => {
    const id = getTaskId();
    if (Number.isFinite(id) && canLoadGithubComments.value) await toggleHistoryBatch(id, batchId);
  };

  const changeGithubCommentHistoryItemsPage = async (batchId: number, direction: "next" | "previous") => {
    const id = getTaskId();
    if (Number.isFinite(id) && canLoadGithubComments.value) await changeHistoryItemsPage(id, batchId, direction);
  };

  const { feedbackSavingId, submitFindingFeedback } = useReviewDetailFindingFeedback({
    canManage: canManageHotTask,
    findingFeedbackPromptTitle,
    isTerminalTask,
    loadGithubCommentPreview: refreshLoadedGithubCommentPreview,
    resetGithubCommentPublishResult,
    selectedTask
  });

  const { confirmPublishGithubComments } = useReviewDetailGithubCommentPublishConfirm({
    canManage,
    canPublishGithubComments,
    githubCommentPreview,
    loadGithubCommentPreview,
    loadGithubCommentPublicationHistory,
    publishGithubCommentsForTask,
    publishingComments,
    selectedTask,
    writebackCheck
  });


  const loadGithubCommentData = async () => {
    const id = getTaskId();
    if (!Number.isFinite(id) || !canLoadGithubComments.value) {
      return;
    }
    await Promise.all([
      loadGithubCommentPreview(id, { page: 1 }),
      loadGithubCommentPublicationHistory(id, { page: 1 })
    ]);
  };


  return {
    canManageHotTask,
    canPublishGithubComments,
    canRetryTask,
    failureReason,
    confirmRetryReview,
    retryingTask,
    canSubmitHumanReview,
    humanReviewPublishBlockReason,
    humanReviewStatusClass,
    humanReviewStatusText,
    submittingHumanReview,
    submitHumanReviewDecision,
    loadGithubCommentPreviewPage,
    loadGithubCommentPreviewCommentableOnly,
    loadGithubCommentPublicationHistoryPage,
    toggleGithubCommentHistoryBatch,
    changeGithubCommentHistoryItemsPage,
    feedbackSavingId,
    submitFindingFeedback,
    confirmPublishGithubComments,
    loadGithubCommentData
  };
};
