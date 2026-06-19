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
        :started-at="reviewTimeline[0]?.time ?? '-'"
        :finding-count="reviewFindings.length"
        :changed-file-count="changedFiles.length"
      />

      <div class="detail-layout">
        <main class="detail-main">
          <ReviewDetailSummaryCard
            :task="selectedTask"
            :finding-count="reviewFindings.length"
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
            :risk-text="riskText"
            :finding-feedback-status-class="findingFeedbackStatusClass"
            :finding-feedback-status-text="findingFeedbackStatusText"
            @feedback="submitFindingFeedback"
          />

          <ReviewDetailGithubCommentsCard
            :can-manage="canManage"
            :can-publish-github-comments="canPublishGithubComments"
            :publishing-comments="publishingComments"
            :human-review-publish-block-reason="humanReviewPublishBlockReason"
            :preview-error="previewError"
            :history-error="historyError"
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
            @publish="confirmPublishGithubComments"
          />

          <ReviewDetailFilesSection
            :missing-tests="missingTests"
            :changed-files="changedFilesWithFindingCounts"
            :change-type-text="changeTypeText"
          />
        </main>

        <ReviewDetailSidePanel
          :task="selectedTask"
          :timeline="localizedTimeline"
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
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { ArrowLeft, ExternalLink, Github, RefreshCw, ShieldAlert } from "lucide-vue-next";
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
  humanReviewStatusClass as mapHumanReviewStatusClass,
  humanReviewStatusText as mapHumanReviewStatusText,
  publicationBatchStatusClass,
  publicationBatchStatusText,
  publicationItemStatusClass,
  publicationMessageText,
  publishStatusText,
  repositoryText,
  sourceText,
  timelineLabelText,
  useReviewDetailFindingFeedback,
  useReviewDetailGithubComments,
  useReviewDetailHumanReview,
  useReviewDetailLoader,
  useReviewDetailPolling,
  useReviewDetailRetry,
  writebackCheckStatusText as mapWritebackCheckStatusText
} from "@/features/review-detail";
import type {
  ChangedFile,
  HumanReviewStatus,
  ReviewStatus,
  RiskLevel,
  TimelineItem
} from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_FAILURES = 3;
const MAX_POLL_INTERVAL_MS = 30000;

const router = useRouter();
const route = useRoute();

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
  loadGithubCommentPreview,
  loadGithubCommentPublicationHistory,
  previewError,
  publicationHistoryBatches,
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
  clearGithubCommentPreviewAndHistory,
  getTaskId: () => Number(route.params.id),
  isTerminalReviewStatus,
  loadGithubCommentPreview,
  loadGithubCommentPublicationHistory,
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

const reviewFindings = computed(() => selectedTask.value?.findings ?? []);
const missingTests = computed(() => selectedTask.value?.missingTests ?? []);
const changedFiles = computed(() => selectedTask.value?.changedFiles ?? []);
const reviewTimeline = computed(() => selectedTask.value?.timeline ?? []);
const riskProfile = computed(() => selectedTask.value?.riskProfile);
const emptyDescription = computed(() => (errorMessage.value ? "审查详情加载失败" : "未找到审查任务"));
const isTerminalTask = computed(() => {
  return isTerminalReviewStatus(selectedTask.value?.status);
});
const shouldPollTask = computed(() => Boolean(selectedTask.value && !isTerminalTask.value));
const canRetryTask = computed(() => selectedTask.value?.status === "failed");
const failureReason = computed(() => selectedTask.value?.failureReason ?? "");
const failureSuggestion = computed(() => selectedTask.value?.failureSuggestion ?? "");
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
const humanReviewStatus = computed<HumanReviewStatus | string>(() => selectedTask.value?.humanReviewStatus ?? "not_required");
const isHumanReviewPublishAllowed = computed(() => {
  if (!selectedTask.value?.humanReviewRequired) {
    return true;
  }
  return humanReviewStatus.value === "approved" || humanReviewStatus.value === "changes_requested";
});
const canSubmitHumanReview = computed(() =>
  Boolean(selectedTask.value?.humanReviewRequired && humanReviewStatus.value === "pending")
);
const humanReviewPublishBlockReason = computed(() => {
  if (!selectedTask.value?.humanReviewRequired || isHumanReviewPublishAllowed.value) {
    return "";
  }
  if (humanReviewStatus.value === "pending") {
    return "当前任务等待人工审查，完成通过或要求修改后才能回写 GitHub 评论。";
  }
  if (humanReviewStatus.value === "rejected") {
    return "当前任务已被人工拒绝，不能回写 GitHub 评论。";
  }
  return "当前任务需要人工审查确认后才能回写 GitHub 评论。";
});
const refreshStatusText = computed(() => {
  if (shouldPollTask.value) {
    if (silentRefreshing.value) {
      return "正在自动刷新任务状态...";
    }
    if (pollErrorMessage.value) {
      if (pollFailureCount.value >= MAX_POLL_FAILURES) {
        return "自动刷新已暂停，请手动刷新";
      }
      return `自动刷新上次失败，将在 ${currentPollIntervalSeconds.value} 秒后重试`;
    }
    return `自动刷新中，每 ${currentPollIntervalSeconds.value} 秒更新一次`;
  }
  if (selectedTask.value?.status === "failed") {
    return lastRefreshedAt.value ? `任务失败，最后更新 ${lastRefreshedAt.value}` : "任务失败";
  }
  return lastRefreshedAt.value ? `已完成，最后更新 ${lastRefreshedAt.value}` : "已完成";
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

const findingCounts = computed<Record<RiskLevel, number>>(() =>
  reviewFindings.value.reduce(
    (counts, finding) => {
      counts[finding.severity] += 1;
      return counts;
    },
    { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
  )
);

const findingCountByFile = computed(() =>
  reviewFindings.value.reduce<Record<string, number>>((counts, finding) => {
    counts[finding.file] = (counts[finding.file] ?? 0) + 1;
    return counts;
  }, {})
);

const changedFilesWithFindingCounts = computed<ChangedFileWithFindingCount[]>(() =>
  changedFiles.value.map((file) => ({
    ...file,
    findingCount: findingCountByFile.value[file.path] ?? 0
  }))
);

const localizedTimeline = computed<TimelineItem[]>(() =>
  reviewTimeline.value.map((item) => ({
    ...item,
    label: timelineLabelText(item.label)
  }))
);

const statusReason = computed(() => {
  if (failureReason.value) {
    return failureReason.value;
  }
  const reasonLabel = reviewTimeline.value
    .map((item) => item.label)
    .find((label) => label.startsWith("Code review generated by rule fallback:") || label.startsWith("Review failed:"));
  if (!reasonLabel) {
    return "";
  }
  return reasonLabel
    .replace("Code review generated by rule fallback:", "")
    .replace("Review failed:", "")
    .trim();
});

const llmModelText = computed(() => {
  if (!selectedTask.value?.llm.model && !selectedTask.value?.llm.provider) {
    return "-";
  }
  return [selectedTask.value.llm.provider, selectedTask.value.llm.model].filter(Boolean).join(" / ");
});

const llmDurationText = computed(() => {
  const durationMs = selectedTask.value?.llm.durationMs;
  if (typeof durationMs === "number" && Number.isFinite(durationMs)) {
    return `${durationMs} ms`;
  }
  return selectedTask.value?.llm.duration ?? "-";
});

const llmTokenUsageText = computed(() => {
  const llm = selectedTask.value?.llm;
  if (!llm || !llm.totalTokens) {
    return "未记录";
  }
  return `${llm.totalTokens} total / ${llm.promptTokens ?? 0} prompt / ${llm.completionTokens ?? 0} completion`;
});

const llmCostText = computed(() => {
  const cost = selectedTask.value?.llm.estimatedCost;
  return cost ? `$${cost}` : "未配置单价";
});

const llmParseStatusText = computed(() => {
  const labels: Record<string, string> = {
    parsed: "解析成功",
    fallback: "规则兜底",
    failed: "解析失败",
    pending: "等待执行"
  };
  const status = selectedTask.value?.llm.parseStatus || (selectedTask.value?.llm.status === "fallback" ? "fallback" : "pending");
  return labels[status] ?? status;
});

const llmParseStatusClass = computed(() => {
  const status = selectedTask.value?.llm.parseStatus || selectedTask.value?.llm.status;
  if (status === "parsed" || status === "completed") {
    return "success";
  }
  if (status === "fallback") {
    return "warning";
  }
  if (status === "failed") {
    return "danger";
  }
  return "pending";
});

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

const humanReviewStatusText = computed(() => {
  return mapHumanReviewStatusText(humanReviewStatus.value);
});

const humanReviewStatusClass = computed(() => {
  return mapHumanReviewStatusClass(humanReviewStatus.value);
});

const { submittingHumanReview, submitHumanReviewDecision } = useReviewDetailHumanReview({
  canSubmitHumanReview,
  humanReviewActionText,
  refreshDetail: () => loadDetail({ silent: true, resetPublishResult: true, force: true }),
  selectedTask
});

const { feedbackSavingId, submitFindingFeedback } = useReviewDetailFindingFeedback({
  canManage,
  findingFeedbackPromptTitle,
  isTerminalTask,
  loadGithubCommentPreview,
  resetGithubCommentPublishResult,
  selectedTask
});

const confirmPublishGithubComments = async () => {
  const preview = githubCommentPreview.value;
  if (!canManage.value || !selectedTask.value || publishingComments.value || !canPublishGithubComments.value || !preview) {
    return;
  }

  try {
    const warningText = writebackCheck.value && writebackCheck.value.status !== "ready"
      ? `\n\n提示：${writebackCheck.value.messages.join(" ")}`
      : "";
    await ElMessageBox.confirm(
      `将向 GitHub PR #${selectedTask.value.prNumber} 回写 ${preview.commentableCount} 条评论。确认继续？${warningText}`,
      "确认回写 GitHub 评论",
      {
        confirmButtonText: "确认回写",
        cancelButtonText: "取消",
        type: "warning"
      }
    );
  } catch {
    return;
  }

  const taskId = selectedTask.value.id;
  await publishGithubCommentsForTask(taskId, async () => {
    await Promise.all([
      loadGithubCommentPreview(taskId),
      loadGithubCommentPublicationHistory(taskId)
    ]);
  });
};

const goBack = () => {
  router.push({ name: "tasks" });
};

const openPrUrl = () => {
  if (selectedTask.value?.prUrl) {
    window.open(selectedTask.value.prUrl, "_blank", "noopener,noreferrer");
  }
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
