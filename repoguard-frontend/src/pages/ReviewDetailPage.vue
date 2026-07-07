<template>
  <div v-loading="loading" class="detail-page">
    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <template v-if="selectedTask">
      <el-alert
        v-if="pollErrorMessage"
        class="page-alert"
        type="warning"
        :title="pollErrorMessage"
        show-icon
        :closable="false"
      />

      <div class="detail-header">
        <div>
          <button class="back-link" type="button" @click="goBack">
            <ArrowLeft :size="18" />
            返回审查任务
          </button>
          <div class="detail-title-row">
            <h1>PR #{{ selectedTask.prNumber }} - {{ selectedTask.title }}</h1>
            <span :class="`status-pill ${statusClass(selectedTask.status)}`">{{ statusText(selectedTask.status) }}</span>
            <span :class="`risk-pill ${selectedTask.riskLevel}`">{{ riskText(selectedTask.riskLevel) }}</span>
          </div>
          <p class="detail-meta">
            <Github :size="20" />
            {{ selectedTask.organization }} / {{ selectedTask.repository }}
            <span>创建时间：{{ selectedTask.createdAt }}</span>
            <span>创建方式：{{ sourceText(selectedTask.source) }}</span>
            <span>触发来源：{{ sourceText(selectedTask.triggerSource) }}</span>
          </p>
          <p class="refresh-meta">
            <RefreshCw :size="15" :class="{ 'refresh-icon-spinning': silentRefreshing }" />
            <span>{{ refreshStatusText }}</span>
          </p>
        </div>
        <div class="detail-actions">
          <el-button size="large" :loading="silentRefreshing" @click="refreshDetail">
            <RefreshCw :size="16" />
            刷新
          </el-button>
          <el-button size="large" @click="openPrUrl">
            在 GitHub 查看
            <ExternalLink :size="16" />
          </el-button>
          <el-tooltip :content="retryTooltip">
            <span>
              <el-button
                type="primary"
                size="large"
                :disabled="!canManage || !canRetryTask"
                :loading="retryingTask"
                @click="confirmRetryReview"
              >
                <RefreshCw :size="16" />
                重试
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <section v-if="failureReason" class="failure-banner">
        <div class="failure-banner-main">
          <span class="failure-banner-icon"><ShieldAlert :size="22" /></span>
          <div>
            <span>审查失败</span>
            <strong>{{ failureReason }}</strong>
            <p v-if="failureSuggestion">{{ failureSuggestion }}</p>
          </div>
        </div>
        <el-button
          type="primary"
          :disabled="!canManage || !canRetryTask"
          :loading="retryingTask"
          @click="confirmRetryReview"
        >
          <RefreshCw :size="16" />
          重试
        </el-button>
      </section>

      <ReviewDetailKpiGrid
        :task="selectedTask"
        :started-at="reviewTimeline[0]?.time ?? selectedTask.createdAt"
        :finding-count="findingTotal"
        :changed-file-count="changedFileTotal"
      />

      <div class="detail-layout">
        <main class="detail-main">
          <ReviewDetailSummaryCard
            :task="selectedTask"
            :finding-count="findingTotal"
            :finding-counts="findingCounts"
            :risk-profile="riskProfile"
            :status-text="statusText"
            :risk-text="riskText"
            :change-type-text="changeTypeText"
          />

          <ReviewDetailHumanReviewCard
            :can-manage="canManage"
            :can-submit-human-review="canSubmitHumanReview"
            :human-review-status-class="humanReviewStatusClass"
            :human-review-status-text="humanReviewStatusText"
            :submitting-human-review="submittingHumanReview"
            :task="selectedTask"
            @submit="submitHumanReviewDecision"
          />

          <ReviewDetailFindingsCard
            :can-manage="canManage"
            :feedback-saving-id="feedbackSavingId"
            :findings="reviewFindings"
            :loaded="findingsLoaded"
            :loading="findingsLoading"
            :current-page="findingsPage"
            :page-size="DETAIL_SECTION_PAGE_SIZE"
            :total="findingTotal"
            :risk-text="riskText"
            :finding-feedback-status-class="findingFeedbackStatusClass"
            :finding-feedback-status-text="findingFeedbackStatusText"
            @load="loadFindingsFirstPage"
            @feedback="submitFindingFeedback"
            @page-change="loadFindingsPage"
          />

          <ReviewDetailGithubCommentsCard
            :can-manage="canManage"
            :can-load-github-comments="isTerminalTask"
            :can-publish-github-comments="canPublishGithubComments"
            :preview-loading="previewLoading"
            :history-loading="historyLoading"
            :publishing-comments="publishingComments"
            :human-review-publish-block-reason="humanReviewPublishBlockReason"
            :preview-error="previewError"
            :history-error="historyError"
            :history-page="historyPage"
            :history-page-size="historyPageSize"
            :history-total="publicationHistoryTotal"
            :preview-commentable-only="previewCommentableOnly"
            :github-comment-preview="githubCommentPreview"
            :github-comment-publish-result="githubCommentPublishResult"
            :writeback-check="writebackCheck"
            :writeback-check-status-class="writebackCheckStatusClass"
            :writeback-check-status-text="writebackCheckStatusText"
            :published-comment-count="publishedCommentCount"
            :publication-history-batches="publicationHistoryBatches"
            :repository-text="repositoryText"
            :comment-preview-key="commentPreviewKey"
            :risk-text="riskText"
            :comment-target-text="commentTargetText"
            :finding-feedback-status-class="findingFeedbackStatusClass"
            :finding-feedback-status-text="findingFeedbackStatusText"
            :comment-block-reason-text="commentBlockReasonText"
            :publication-item-status-class="publicationItemStatusClass"
            :publish-status-text="publishStatusText"
            :publication-message-text="publicationMessageText"
            :publication-batch-status-class="publicationBatchStatusClass"
            :publication-batch-status-text="publicationBatchStatusText"
            @load-preview="loadGithubCommentData"
            @history-page-change="loadGithubCommentPublicationHistoryPage"
            @preview-commentable-only-change="loadGithubCommentPreviewCommentableOnly"
            @preview-page-change="loadGithubCommentPreviewPage"
            @publish="confirmPublishGithubComments"
          />

          <ReviewDetailFilesSection
            :missing-tests="missingTests"
            :changed-files="changedFilesWithFindingCounts"
            :missing-tests-loaded="missingTestsLoaded"
            :changed-files-loaded="changedFilesLoaded"
            :missing-tests-loading="missingTestsLoading"
            :changed-files-loading="changedFilesLoading"
            :missing-tests-page="missingTestsPage"
            :changed-files-page="changedFilesPage"
            :page-size="DETAIL_SECTION_PAGE_SIZE"
            :missing-tests-total="missingTestTotal"
            :changed-files-total="changedFileTotal"
            :change-type-text="changeTypeText"
            @missing-tests-load="loadMissingTestsFirstPage"
            @changed-files-load="loadChangedFilesFirstPage"
            @missing-tests-page-change="loadMissingTestsPage"
            @changed-files-page-change="loadChangedFilesPage"
          />
        </main>

        <ReviewDetailSidePanel
          :task="selectedTask"
          :timeline="localizedTimeline"
          :timeline-loaded="timelineLoaded || localizedTimeline.length > 0"
          :timeline-loading="timelineLoading"
          :llm-model-text="llmModelText"
          :llm-parse-status-text="llmParseStatusText"
          :llm-parse-status-class="llmParseStatusClass"
          :llm-duration-text="llmDurationText"
          :llm-token-usage-text="llmTokenUsageText"
          :llm-cost-text="llmCostText"
          :status-reason="statusReason"
          :status-text="statusText"
          :status-class="statusClass"
          :risk-text="riskText"
          :chunk-aggregate-risk-text="chunkAggregateRiskText"
          :chunk-reason-text="chunkReasonText"
          :consume-status-text="consumeStatusText"
          @timeline-load="loadTimelineItems"
        />
      </div>
    </template>
    <el-empty v-else-if="!loading" :description="emptyDescription">
      <el-button type="primary" plain @click="goBack">返回列表</el-button>
      <el-button :loading="loading" @click="loadDetail">重新加载</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ArrowLeft, ExternalLink, Github, RefreshCw, ShieldAlert } from "lucide-vue-next";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewChangedFiles, fetchReviewFindings, fetchReviewMissingTests, fetchReviewTimeline } from "@/api/reviews";
import { canManage } from "@/stores/authState";
import { useRoute, useRouter } from "vue-router";
import {
  ReviewDetailFilesSection,
  ReviewDetailFindingsCard,
  ReviewDetailGithubCommentsCard,
  ReviewDetailHumanReviewCard,
  ReviewDetailKpiGrid,
  ReviewDetailSidePanel,
  ReviewDetailSummaryCard,
  changeTypeText,
  chunkAggregateRiskText,
  chunkReasonText,
  commentBlockReasonText,
  commentPreviewKey,
  commentTargetText,
  consumeStatusText,
  findingFeedbackPromptTitle,
  findingFeedbackStatusClass,
  findingFeedbackStatusText,
  humanReviewActionText,
  publicationBatchStatusClass,
  publicationBatchStatusText,
  publicationItemStatusClass,
  publicationMessageText,
  publishStatusText,
  refreshStatusText as mapRefreshStatusText,
  repositoryText,
  sourceText,
  useReviewDetailDerivedCollections,
  useReviewDetailFindingFeedback,
  useReviewDetailGithubCommentPublishConfirm,
  useReviewDetailGithubComments,
  useReviewDetailHumanReview,
  useReviewDetailHumanReviewDisplay,
  useReviewDetailLoader,
  useReviewDetailLlmDisplay,
  useReviewDetailPolling,
  useReviewDetailRetry,
  writebackCheckStatusText as mapWritebackCheckStatusText
} from "@/features/review-detail";
import type { ReviewStatus } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_FAILURES = 3;
const MAX_POLL_INTERVAL_MS = 30000;
const DETAIL_SECTION_PAGE_SIZE = 20;

const router = useRouter();
const route = useRoute();

const findingsPage = ref(1);
const changedFilesPage = ref(1);
const missingTestsPage = ref(1);
const findingsLoading = ref(false);
const changedFilesLoading = ref(false);
const missingTestsLoading = ref(false);
const timelineLoading = ref(false);
const findingsLoaded = ref(false);
const changedFilesLoaded = ref(false);
const missingTestsLoaded = ref(false);
const timelineLoaded = ref(false);

const isTerminalReviewStatus = (status?: ReviewStatus | string) =>
  status === "completed"
    || status === "failed"
    || status === "pending_human_review"
    || status === "approved"
    || status === "changes_requested"
    || status === "rejected";

const {
  githubCommentPreview,
  githubCommentPublishResult,
  historyError,
  historyPage,
  historyPageSize,
  historyLoading,
  loadGithubCommentPreview,
  loadGithubCommentPublicationHistory,
  previewError,
  previewCommentableOnly,
  previewLoading,
  publicationHistoryBatches,
  publicationHistoryTotal,
  publishedCommentCount,
  publishingComments,
  writebackCheck,
  clearGithubCommentPreviewAndHistory,
  clearGithubCommentState,
  publishGithubCommentsForTask,
  resetGithubCommentPublishResult
} = useReviewDetailGithubComments();
let stopPolling = () => {};
let syncPolling = () => {};

const resetDetailSectionPages = () => {
  findingsPage.value = 1;
  changedFilesPage.value = 1;
  missingTestsPage.value = 1;
  findingsLoaded.value = false;
  changedFilesLoaded.value = false;
  missingTestsLoaded.value = false;
  timelineLoaded.value = false;
};

function afterDetailSummaryLoaded() {
  resetDetailSectionPages();
}

const {
  errorMessage,
  lastRefreshedAt,
  loading,
  loadDetail,
  pollErrorMessage,
  pollFailureCount,
  pollReviewStatus,
  refreshDetail,
  selectedTask,
  silentRefreshing
} = useReviewDetailLoader({
  afterDetailLoaded: afterDetailSummaryLoaded,
  clearGithubCommentPreviewAndHistory,
  getTaskId: () => Number(route.params.id),
  isTerminalReviewStatus,
  maxPollFailures: MAX_POLL_FAILURES,
  resetGithubCommentPublishResult,
  stopPolling: () => stopPolling(),
  syncPolling: () => syncPolling()
});
const canPublishGithubComments = computed(() =>
  Boolean(
    canManage.value
      && githubCommentPreview.value?.commentableCount
      && writebackCheck.value?.tokenConfigured !== false
      && isHumanReviewPublishAllowed.value
  )
);

const emptyDescription = computed(() => (errorMessage.value ? "审查详情加载失败" : "未找到审查任务"));
const isTerminalTask = computed(() => {
  return isTerminalReviewStatus(selectedTask.value?.status);
});
const shouldPollTask = computed(() => Boolean(selectedTask.value && !isTerminalTask.value));
const canRetryTask = computed(() => selectedTask.value?.status === "failed");
const failureReason = computed(() => selectedTask.value?.failureReason ?? "");
const failureSuggestion = computed(() => selectedTask.value?.failureSuggestion ?? "");
const {
  changedFiles,
  changedFilesWithFindingCounts,
  findingCounts,
  localizedTimeline,
  missingTests,
  reviewFindings,
  reviewTimeline,
  riskProfile,
  statusReason
} = useReviewDetailDerivedCollections({
  failureReason,
  selectedTask
});
const findingTotal = computed(() => selectedTask.value?.findingTotal ?? reviewFindings.value.length);
const missingTestTotal = computed(() => selectedTask.value?.missingTestTotal ?? missingTests.value.length);
const changedFileTotal = computed(() => selectedTask.value?.changedFileTotal ?? changedFiles.value.length);
const {
  llmCostText,
  llmDurationText,
  llmModelText,
  llmParseStatusClass,
  llmParseStatusText,
  llmTokenUsageText
} = useReviewDetailLlmDisplay({
  selectedTask
});
const retryTooltip = computed(() => {
  if (!canRetryTask.value) {
    return "仅失败任务支持重试";
  }
  return failureSuggestion.value || "重新入队执行审查";
});
const { confirmRetryReview, retryingTask } = useReviewDetailRetry({
  canManage,
  canRetryTask,
  clearGithubCommentState,
  failureReason,
  refreshDetail: () => loadDetail({ silent: true, resetPublishResult: true }),
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
const refreshStatusText = computed(() => {
  return mapRefreshStatusText({
    currentPollIntervalSeconds: currentPollIntervalSeconds.value,
    lastRefreshedAt: lastRefreshedAt.value,
    pollErrorMessage: pollErrorMessage.value,
    pollFailureCount: pollFailureCount.value,
    selectedTaskStatus: selectedTask.value?.status,
    shouldPollTask: shouldPollTask.value,
    silentRefreshing: silentRefreshing.value,
    maxPollFailures: MAX_POLL_FAILURES
  });
});

const currentPollIntervalMs = computed(() =>
  Math.min(POLL_INTERVAL_MS * 2 ** pollFailureCount.value, MAX_POLL_INTERVAL_MS)
);
const currentPollIntervalSeconds = computed(() => currentPollIntervalMs.value / 1000);

const polling = useReviewDetailPolling({
  currentPollIntervalMs,
  maxPollFailures: MAX_POLL_FAILURES,
  pollFailureCount,
  pollReviewStatus: () => pollReviewStatus(),
  shouldPollTask
});
const { cleanupPolling } = polling;
stopPolling = polling.stopPolling;
syncPolling = polling.syncPolling;

const writebackCheckStatusText = computed(() => {
  return writebackCheck.value ? mapWritebackCheckStatusText(writebackCheck.value.status) : "";
});

const writebackCheckStatusClass = computed(() => {
  const level = writebackCheck.value?.level;
  if (level === "success") {
    return "success";
  }
  if (level === "warning") {
    return "warning";
  }
  return "danger";
});

const { submittingHumanReview, submitHumanReviewDecision } = useReviewDetailHumanReview({
  canSubmitHumanReview,
  humanReviewActionText,
  refreshDetail: () => loadDetail({ silent: true, resetPublishResult: true, force: true }),
  selectedTask
});

const refreshLoadedGithubCommentPreview = async (id: number) => {
  if (!githubCommentPreview.value) {
    return;
  }
  await loadGithubCommentPreview(id);
};

const loadGithubCommentPreviewPage = async (page: number) => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id) || !isTerminalTask.value || !githubCommentPreview.value) {
    return;
  }
  await loadGithubCommentPreview(id, { page });
};

const loadGithubCommentPreviewCommentableOnly = async (commentableOnly: boolean) => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id) || !isTerminalTask.value) {
    return;
  }
  await loadGithubCommentPreview(id, { page: 1, commentableOnly });
};

const loadGithubCommentPublicationHistoryPage = async (page: number) => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id) || !isTerminalTask.value) {
    return;
  }
  await loadGithubCommentPublicationHistory(id, { page });
};

const loadFindingsPage = async (page: number) => {
  if (!selectedTask.value || findingsLoading.value) {
    return;
  }
  const taskId = selectedTask.value.id;
  findingsLoading.value = true;
  try {
    const result = await fetchReviewFindings(taskId, {
      page,
      pageSize: DETAIL_SECTION_PAGE_SIZE
    });
    if (!selectedTask.value || selectedTask.value.id !== taskId) {
      return;
    }
    selectedTask.value = {
      ...selectedTask.value,
      findings: result.items,
      findingTotal: result.total
    };
    findingsPage.value = page;
    findingsLoaded.value = true;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    findingsLoading.value = false;
  }
};

const loadChangedFilesPage = async (page: number) => {
  if (!selectedTask.value || changedFilesLoading.value) {
    return;
  }
  const taskId = selectedTask.value.id;
  changedFilesLoading.value = true;
  try {
    const result = await fetchReviewChangedFiles(taskId, {
      page,
      pageSize: DETAIL_SECTION_PAGE_SIZE
    });
    if (!selectedTask.value || selectedTask.value.id !== taskId) {
      return;
    }
    selectedTask.value = {
      ...selectedTask.value,
      changedFiles: result.items,
      changedFileTotal: result.total
    };
    changedFilesPage.value = page;
    changedFilesLoaded.value = true;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    changedFilesLoading.value = false;
  }
};

const loadMissingTestsPage = async (page: number) => {
  if (!selectedTask.value || missingTestsLoading.value) {
    return;
  }
  const taskId = selectedTask.value.id;
  missingTestsLoading.value = true;
  try {
    const result = await fetchReviewMissingTests(taskId, {
      page,
      pageSize: DETAIL_SECTION_PAGE_SIZE
    });
    if (!selectedTask.value || selectedTask.value.id !== taskId) {
      return;
    }
    selectedTask.value = {
      ...selectedTask.value,
      missingTests: result.items,
      missingTestTotal: result.total
    };
    missingTestsPage.value = page;
    missingTestsLoaded.value = true;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    missingTestsLoading.value = false;
  }
};

const loadTimelineItems = async () => {
  if (!selectedTask.value || timelineLoading.value) {
    return;
  }
  const taskId = selectedTask.value.id;
  timelineLoading.value = true;
  try {
    const timeline = await fetchReviewTimeline(taskId, { limit: 20 });
    if (!selectedTask.value || selectedTask.value.id !== taskId) {
      return;
    }
    selectedTask.value = {
      ...selectedTask.value,
      timeline
    };
    timelineLoaded.value = true;
  } catch (error) {
    ElMessage.warning(getErrorMessage(error, "时间线加载失败"));
  } finally {
    timelineLoading.value = false;
  }
};

const loadFindingsFirstPage = () => loadFindingsPage(1);

const loadChangedFilesFirstPage = () => loadChangedFilesPage(1);

const loadMissingTestsFirstPage = () => loadMissingTestsPage(1);

const { feedbackSavingId, submitFindingFeedback } = useReviewDetailFindingFeedback({
  canManage,
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

const goBack = () => {
  router.push({ name: "tasks" });
};

const openPrUrl = () => {
  if (selectedTask.value?.prUrl) {
    window.open(selectedTask.value.prUrl, "_blank", "noopener,noreferrer");
  }
};

const loadGithubCommentData = async () => {
  const id = Number(route.params.id);
  if (!Number.isFinite(id) || !isTerminalTask.value) {
    return;
  }
  await Promise.all([
    loadGithubCommentPreview(id, { page: 1 }),
    loadGithubCommentPublicationHistory(id, { page: 1 })
  ]);
};

watch(
  () => route.params.id,
  () => {
    stopPolling();
    void loadDetail();
  }
);

onMounted(() => {
  void loadDetail();
});

onBeforeUnmount(() => {
  cleanupPolling();
});
</script>
