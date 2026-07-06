import { computed, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchGithubCommentPreview, fetchGithubCommentPublicationHistory, publishGithubComments } from "@/api/reviews";
import type { GithubCommentPreview, GithubCommentPublicationBatch, GithubCommentPublicationHistory, GithubCommentPublish } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const GITHUB_COMMENT_PREVIEW_PAGE_SIZE = 10;
const GITHUB_COMMENT_HISTORY_PAGE_SIZE = 5;

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

  const publishedCommentCount = computed(() =>
    githubCommentPreview.value?.publishedCount
      ?? githubCommentPreview.value?.items.filter((item) => item.published).length
      ?? 0
  );
  const publicationHistoryBatches = computed<GithubCommentPublicationBatch[]>(() => githubCommentPublicationHistory.value?.batches ?? []);
  const publicationHistoryTotal = computed(() => githubCommentPublicationHistory.value?.total ?? 0);
  const writebackCheck = computed(() => githubCommentPreview.value?.writebackCheck);

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

  const loadGithubCommentPublicationHistory = async (id: number, options: GithubCommentHistoryLoadOptions = {}) => {
    historyError.value = "";
    historyLoading.value = true;
    const page = options.page ?? historyPage.value;
    const status = options.status ?? historyStatus.value;
    try {
      githubCommentPublicationHistory.value = await fetchGithubCommentPublicationHistory(id, {
        page,
        pageSize: historyPageSize,
        status: status || undefined
      });
      historyPage.value = githubCommentPublicationHistory.value.page;
      historyStatus.value = githubCommentPublicationHistory.value.status ?? "";
    } catch (error) {
      githubCommentPublicationHistory.value = null;
      historyError.value = getErrorMessage(error, "请求失败");
    } finally {
      historyLoading.value = false;
    }
  };

  const clearGithubCommentPreviewAndHistory = () => {
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
    historyPage,
    historyPageSize,
    historyStatus,
    historyLoading,
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
    resetGithubCommentPublishResult
  };
};
