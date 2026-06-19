import { computed, ref } from "vue";
import { fetchGithubCommentPreview, fetchGithubCommentPublicationHistory } from "@/api/reviews";
import type { GithubCommentPreview, GithubCommentPublicationBatch, GithubCommentPublicationHistory, GithubCommentPublish } from "@/types";
import { getErrorMessage } from "@/utils/errors";

export const useReviewDetailGithubComments = () => {
  const previewError = ref("");
  const historyError = ref("");
  const githubCommentPreview = ref<GithubCommentPreview | null>(null);
  const githubCommentPublicationHistory = ref<GithubCommentPublicationHistory | null>(null);
  const githubCommentPublishResult = ref<GithubCommentPublish | null>(null);

  const publishedCommentCount = computed(() => githubCommentPreview.value?.items.filter((item) => item.published).length ?? 0);
  const publicationHistoryBatches = computed<GithubCommentPublicationBatch[]>(() => githubCommentPublicationHistory.value?.batches ?? []);
  const writebackCheck = computed(() => githubCommentPreview.value?.writebackCheck);

  const loadGithubCommentPreview = async (id: number) => {
    previewError.value = "";
    try {
      githubCommentPreview.value = await fetchGithubCommentPreview(id);
    } catch (error) {
      githubCommentPreview.value = null;
      previewError.value = getErrorMessage(error, "请求失败");
    }
  };

  const loadGithubCommentPublicationHistory = async (id: number) => {
    historyError.value = "";
    try {
      githubCommentPublicationHistory.value = await fetchGithubCommentPublicationHistory(id);
    } catch (error) {
      githubCommentPublicationHistory.value = null;
      historyError.value = getErrorMessage(error, "请求失败");
    }
  };

  const clearGithubCommentPreviewAndHistory = () => {
    previewError.value = "";
    githubCommentPreview.value = null;
    historyError.value = "";
    githubCommentPublicationHistory.value = null;
  };

  const clearGithubCommentState = () => {
    clearGithubCommentPreviewAndHistory();
    githubCommentPublishResult.value = null;
  };

  const resetGithubCommentPublishResult = () => {
    githubCommentPublishResult.value = null;
  };

  return {
    githubCommentPreview,
    githubCommentPublicationHistory,
    githubCommentPublishResult,
    historyError,
    previewError,
    publicationHistoryBatches,
    publishedCommentCount,
    writebackCheck,
    clearGithubCommentPreviewAndHistory,
    clearGithubCommentState,
    loadGithubCommentPreview,
    loadGithubCommentPublicationHistory,
    resetGithubCommentPublishResult
  };
};
