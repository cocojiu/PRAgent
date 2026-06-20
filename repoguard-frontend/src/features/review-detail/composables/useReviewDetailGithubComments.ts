import { computed, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchGithubCommentPreview, fetchGithubCommentPublicationHistory, publishGithubComments } from "@/api/reviews";
import type { GithubCommentPreview, GithubCommentPublicationBatch, GithubCommentPublicationHistory, GithubCommentPublish } from "@/types";
import { getErrorMessage } from "@/utils/errors";

export const useReviewDetailGithubComments = () => {
  const previewError = ref("");
  const historyError = ref("");
  const publishingComments = ref(false);
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

  const publishGithubCommentsForTask = async (id: number, afterPublish?: () => Promise<void>) => {
    publishingComments.value = true;
    try {
      githubCommentPublishResult.value = await publishGithubComments(id);
      const result = githubCommentPublishResult.value;
      if (result.failedCount > 0) {
        ElMessage.warning(`GitHub 评论回写完成：成功 ${result.succeededCount} 条，失败 ${result.failedCount} 条`);
      } else {
        ElMessage.success(`GitHub 评论回写成功：${result.succeededCount} 条`);
      }
      await afterPublish?.();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      publishingComments.value = false;
    }
  };

  return {
    githubCommentPreview,
    githubCommentPublicationHistory,
    githubCommentPublishResult,
    historyError,
    previewError,
    publicationHistoryBatches,
    publishedCommentCount,
    publishingComments,
    writebackCheck,
    clearGithubCommentPreviewAndHistory,
    clearGithubCommentState,
    loadGithubCommentPreview,
    loadGithubCommentPublicationHistory,
    publishGithubCommentsForTask,
    resetGithubCommentPublishResult
  };
};
