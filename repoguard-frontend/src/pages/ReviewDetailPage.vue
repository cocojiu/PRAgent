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

          <article v-if="selectedTask.humanReviewRequired" class="dashboard-card human-review-card">
            <div class="card-title-row">
              <h2>人工审查门禁</h2>
              <span :class="`status-pill ${humanReviewStatusClass}`">{{ humanReviewStatusText }}</span>
            </div>
            <p class="human-review-note">
              {{ selectedTask.humanReviewNote || "中高风险审查结果需要人工确认后才能回写 GitHub 评论。" }}
            </p>
            <dl v-if="selectedTask.humanReviewBy || selectedTask.humanReviewedAt" class="human-review-meta">
              <dt>审查人</dt><dd>{{ selectedTask.humanReviewBy || "-" }}</dd>
              <dt>审查时间</dt><dd>{{ selectedTask.humanReviewedAt || "-" }}</dd>
            </dl>
            <div class="human-review-actions">
              <el-button
                type="success"
                :disabled="!canManage || !canSubmitHumanReview"
                :loading="submittingHumanReview"
                @click="submitHumanReviewDecision('approve')"
              >
                通过审查
              </el-button>
              <el-button
                type="warning"
                :disabled="!canManage || !canSubmitHumanReview"
                :loading="submittingHumanReview"
                @click="submitHumanReviewDecision('changes_requested')"
              >
                要求修改
              </el-button>
              <el-button
                type="danger"
                plain
                :disabled="!canManage || !canSubmitHumanReview"
                :loading="submittingHumanReview"
                @click="submitHumanReviewDecision('reject')"
              >
                拒绝
              </el-button>
            </div>
          </article>

          <article class="dashboard-card findings-card">
            <div class="card-title-row">
              <h2>LLM Findings</h2>
              <span class="count-badge">{{ reviewFindings.length }} 条</span>
            </div>
            <div v-if="reviewFindings.length" class="finding-list">
              <section v-for="(finding, index) in reviewFindings" :key="`${finding.file}-${finding.line}-${index}`" class="finding-item">
                <div class="finding-head">
                  <span :class="`risk-pill ${finding.severity}`">{{ riskText(finding.severity) }}</span>
                  <code>{{ finding.file }}</code>
                  <span class="line-badge">L{{ finding.line ?? "-" }}</span>
                  <span :class="`status-pill ${findingFeedbackStatusClass(finding.feedbackStatus)}`">
                    {{ findingFeedbackStatusText(finding.feedbackStatus) }}
                  </span>
                </div>
                <div class="finding-body">
                  <div>
                    <h3>问题描述</h3>
                    <p>{{ finding.message || "暂无问题描述" }}</p>
                  </div>
                  <div>
                    <h3>修复建议</h3>
                    <p>{{ finding.recommendation || "暂无修复建议" }}</p>
                  </div>
                </div>
                <div class="finding-feedback">
                  <p v-if="finding.feedbackNote || finding.feedbackBy || finding.feedbackAt" class="finding-feedback-meta">
                    {{ finding.feedbackBy || "admin" }} · {{ finding.feedbackAt || "刚刚" }}
                    <span v-if="finding.feedbackNote"> · {{ finding.feedbackNote }}</span>
                  </p>
                  <div class="finding-feedback-actions">
                    <el-button
                      size="small"
                      type="success"
                      plain
                      :disabled="!canManage || !finding.id"
                      :loading="feedbackSavingId === finding.id"
                      @click="submitFindingFeedback(finding.id, 'valid')"
                    >
                      有效
                    </el-button>
                    <el-button
                      size="small"
                      type="warning"
                      plain
                      :disabled="!canManage || !finding.id"
                      :loading="feedbackSavingId === finding.id"
                      @click="submitFindingFeedback(finding.id, 'false_positive')"
                    >
                      误报
                    </el-button>
                    <el-button
                      size="small"
                      plain
                      :disabled="!canManage || !finding.id"
                      :loading="feedbackSavingId === finding.id"
                      @click="submitFindingFeedback(finding.id, 'fixed')"
                    >
                      已修复
                    </el-button>
                    <el-button
                      size="small"
                      type="info"
                      plain
                      :disabled="!canManage || !finding.id"
                      :loading="feedbackSavingId === finding.id"
                      @click="submitFindingFeedback(finding.id, 'ignored')"
                    >
                      忽略
                    </el-button>
                  </div>
                </div>
              </section>
            </div>
            <el-empty v-else description="暂无审查问题" />
          </article>

          <article class="dashboard-card github-preview-card">
            <div class="card-title-row">
              <h2>GitHub 评论预览</h2>
              <el-button
                type="primary"
                :disabled="!canManage || !canPublishGithubComments"
                :loading="publishingComments"
                @click="confirmPublishGithubComments"
              >
                <Github :size="16" />
                回写到 GitHub
              </el-button>
            </div>
            <el-alert
              v-if="humanReviewPublishBlockReason"
              class="preview-alert"
              type="warning"
              :title="humanReviewPublishBlockReason"
              show-icon
              :closable="false"
            />
            <el-alert
              v-if="previewError"
              class="preview-alert"
              type="warning"
              :title="previewError"
              show-icon
              :closable="false"
            />
            <template v-if="githubCommentPreview">
              <div v-if="writebackCheck" :class="['writeback-check', `writeback-check--${writebackCheck.level}`]">
                <div class="writeback-check-head">
                  <span :class="`status-pill ${writebackCheckStatusClass}`">{{ writebackCheckStatusText }}</span>
                  <span>任务仓库：{{ repositoryText(writebackCheck.taskOwner, writebackCheck.taskRepository) }}</span>
                  <span>配置仓库：{{ repositoryText(writebackCheck.configuredOwner, writebackCheck.configuredRepository) }}</span>
                </div>
                <p v-for="message in writebackCheck.messages" :key="message">{{ message }}</p>
                <RouterLink
                  v-if="writebackCheck.status !== 'ready'"
                  class="writeback-check-link"
                  :to="{ name: 'integrations' }"
                >
                  前往集成配置
                </RouterLink>
              </div>
              <div class="comment-preview-summary">
                <span>总审查发现：{{ githubCommentPreview.totalFindings }}</span>
                <span>可回写：{{ githubCommentPreview.commentableCount }}</span>
                <span>已发布：{{ publishedCommentCount }}</span>
                <span>不可回写：{{ githubCommentPreview.blockedCount }}</span>
              </div>
              <div v-if="githubCommentPublishResult" class="comment-publish-summary">
                <span>已尝试：{{ githubCommentPublishResult.attemptedCount }}</span>
                <span>成功：{{ githubCommentPublishResult.succeededCount }}</span>
                <span>失败：{{ githubCommentPublishResult.failedCount }}</span>
                <span>跳过：{{ githubCommentPublishResult.skippedCount }}</span>
              </div>
              <div v-if="githubCommentPreview.items.length" class="comment-preview-list">
                <section
                  v-for="item in githubCommentPreview.items"
                  :key="commentPreviewKey(item)"
                  :class="['comment-preview-item', { blocked: !item.commentable }]"
                >
                  <div class="comment-preview-head">
                    <span :class="`risk-pill ${item.severity}`">{{ riskText(item.severity) }}</span>
                    <code>{{ item.file }}</code>
                    <span v-if="item.line" class="line-badge">L{{ item.line }}</span>
                    <span class="count-badge">{{ commentTargetText(item.targetType) }}</span>
                    <span :class="`status-pill ${findingFeedbackStatusClass(item.feedbackStatus)}`">
                      {{ findingFeedbackStatusText(item.feedbackStatus) }}
                    </span>
                    <span :class="`status-pill ${item.published ? 'success' : item.commentable ? 'success' : 'warning'}`">
                      {{ item.published ? "已发布" : item.commentable ? "可回写" : "需处理" }}
                    </span>
                  </div>
                  <p v-if="!item.commentable" class="comment-preview-reason">{{ commentBlockReasonText(item.reason) }}</p>
                  <a
                    v-if="item.publicationUrl"
                    class="comment-preview-link"
                    :href="item.publicationUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    查看已发布评论
                  </a>
                  <pre>{{ item.commentBody }}</pre>
                </section>
              </div>
              <el-empty v-else description="暂无评论草稿" />
              <div v-if="githubCommentPublishResult?.items.length" class="comment-result-list">
                <section
                  v-for="item in githubCommentPublishResult.items"
                  :key="`${item.findingId ?? item.targetType}-${item.status}`"
                  :class="['comment-result-item', publicationItemStatusClass(item.status)]"
                >
                  <div>
                    <strong>{{ item.file }}{{ item.line ? `:L${item.line}` : "" }}</strong>
                    <span>{{ publishStatusText(item.status) }}</span>
                  </div>
                  <p>{{ publicationMessageText(item.message, item.status) }}</p>
                  <div v-if="item.failureReason || item.failureSuggestion" class="publication-failure-note">
                    <strong v-if="item.failureReason">{{ item.failureReason }}</strong>
                    <span v-if="item.failureSuggestion">{{ item.failureSuggestion }}</span>
                  </div>
                  <a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">查看 GitHub 评论</a>
                </section>
              </div>
              <el-alert
                v-if="historyError"
                class="preview-alert"
                type="warning"
                :title="historyError"
                show-icon
                :closable="false"
              />
              <div v-if="publicationHistoryBatches.length" class="comment-history">
                <div class="comment-history-title">
                  <h3>回写历史</h3>
                  <span>{{ publicationHistoryBatches.length }} 次</span>
                </div>
                <section v-for="batch in publicationHistoryBatches" :key="batch.batchId" class="comment-history-batch">
                  <div class="comment-history-batch-head">
                    <div>
                      <strong>{{ batch.createdAt }}</strong>
                      <span :class="`status-pill ${publicationBatchStatusClass(batch.status)}`">
                        {{ publicationBatchStatusText(batch.status) }}
                      </span>
                    </div>
                    <div class="comment-history-counts">
                      <span>尝试 {{ batch.attemptedCount }}</span>
                      <span>成功 {{ batch.succeededCount }}</span>
                      <span>失败 {{ batch.failedCount }}</span>
                      <span>跳过 {{ batch.skippedCount }}</span>
                    </div>
                  </div>
                  <div class="comment-history-items">
                    <div
                      v-for="item in batch.items"
                      :key="`${batch.batchId}-${item.findingId}-${item.status}`"
                      :class="['comment-history-item', publicationItemStatusClass(item.status)]"
                    >
                      <div>
                        <strong>{{ item.file || "PR 总评" }}{{ item.line ? `:L${item.line}` : "" }}</strong>
                        <span>{{ publishStatusText(item.status) }}</span>
                      </div>
                      <p>{{ publicationMessageText(item.message, item.status) }}</p>
                      <div v-if="item.failureReason || item.failureSuggestion" class="publication-failure-note">
                        <strong v-if="item.failureReason">{{ item.failureReason }}</strong>
                        <span v-if="item.failureSuggestion">{{ item.failureSuggestion }}</span>
                      </div>
                      <a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">查看 GitHub 评论</a>
                    </div>
                  </div>
                </section>
              </div>
              <el-empty v-else-if="!historyError" description="暂无回写历史" />
            </template>
            <el-empty v-else-if="!previewError" description="评论预览加载中" />
          </article>

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
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { ArrowLeft, ExternalLink, Github, RefreshCw, ShieldAlert } from "lucide-vue-next";
import { canManage } from "@/stores/authState";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { ReviewDetailFilesSection, ReviewDetailKpiGrid, ReviewDetailSidePanel, ReviewDetailSummaryCard } from "@/features/review-detail";
import {
  fetchGithubCommentPreview,
  fetchGithubCommentPublicationHistory,
  fetchReviewDetail,
  fetchReviewStatus,
  publishGithubComments,
  retryReview,
  submitHumanReview,
  updateFindingFeedback
} from "@/api/reviews";
import type {
  ChangedFile,
  FindingFeedbackResponse,
  FindingFeedbackStatus,
  GithubCommentPreview,
  GithubCommentPublicationBatch,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  HumanReviewRequest,
  HumanReviewStatus,
  ReviewStatus,
  ReviewTaskDetail,
  ReviewTaskStatus,
  RiskLevel,
  TimelineItem
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };
type LoadDetailOptions = { silent?: boolean; resetPublishResult?: boolean; force?: boolean };

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_FAILURES = 3;
const MAX_POLL_INTERVAL_MS = 30000;

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const silentRefreshing = ref(false);
const publishingComments = ref(false);
const submittingHumanReview = ref(false);
const feedbackSavingId = ref<number | null>(null);
const retryingTask = ref(false);
const errorMessage = ref("");
const previewError = ref("");
const historyError = ref("");
const pollErrorMessage = ref("");
const pollFailureCount = ref(0);
const lastRefreshedAt = ref("");
const selectedTask = ref<ReviewTaskDetail | null>(null);
const githubCommentPreview = ref<GithubCommentPreview | null>(null);
const githubCommentPublicationHistory = ref<GithubCommentPublicationHistory | null>(null);
const githubCommentPublishResult = ref<GithubCommentPublish | null>(null);
const publishedCommentCount = computed(() => githubCommentPreview.value?.items.filter((item) => item.published).length ?? 0);
const publicationHistoryBatches = computed<GithubCommentPublicationBatch[]>(() => githubCommentPublicationHistory.value?.batches ?? []);
const writebackCheck = computed(() => githubCommentPreview.value?.writebackCheck);
const canPublishGithubComments = computed(() =>
  Boolean(
    canManage.value
      && githubCommentPreview.value?.commentableCount
      && writebackCheck.value?.tokenConfigured !== false
      && isHumanReviewPublishAllowed.value
  )
);
let pollTimer: ReturnType<typeof setTimeout> | undefined;

const isTerminalReviewStatus = (status?: ReviewStatus | string) =>
  status === "completed"
    || status === "failed"
    || status === "pending_human_review"
    || status === "approved"
    || status === "changes_requested"
    || status === "rejected";

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

const chunkAggregateRiskText = (risk?: RiskLevel | string) => riskText((risk || "info") as RiskLevel);

const chunkReasonText = (reason: string) => {
  const labels: Record<string, string> = {
    sensitive_path: "敏感路径",
    large_pr: "大 PR",
    file_count: "文件数较多",
    line_count: "变更行数较多",
    security: "认证权限",
    config: "运行配置",
    build: "构建发布",
    database: "数据库变更",
    database_migration: "数据库迁移",
    security_sensitive: "认证权限",
    runtime_config: "运行配置",
    delivery_pipeline: "构建发布",
    multi_file: "多文件变更",
    large_churn: "变更规模较大",
    standard: "常规分片"
  };
  return labels[reason] ?? reason;
};

const timelineLabelText = (label: string) => {
  if (label === "Task queued") {
    return "任务已入队";
  }
  if (label === "Review started") {
    return "开始审查";
  }
  if (label === "GitHub diff fetched") {
    return "已拉取 GitHub Diff";
  }
  if (label === "Code review generated" || label === "Code review generated by LLM") {
    return "LLM 已生成审查结果";
  }
  if (label === "Code review generated by rule fallback") {
    return "规则兜底已生成审查结果";
  }
  if (label.startsWith("Code review generated by rule fallback:")) {
    return `规则兜底已生成审查结果：${label.replace("Code review generated by rule fallback:", "").trim()}`;
  }
  if (label === "Review completed") {
    return "审查完成";
  }
  if (label === "Review failed") {
    return "审查失败";
  }
  if (label.startsWith("Review failed:")) {
    return `审查失败：${label.replace("Review failed:", "").trim()}`;
  }
  return label;
};

const changeTypeText = (type: ChangedFile["changeType"] | string) => {
  const labels: Record<string, string> = {
    A: "新增",
    M: "修改",
    D: "删除",
    ADD: "新增",
    MODIFY: "修改",
    DELETE: "删除",
    RENAMED: "重命名"
  };
  return labels[type] ?? type;
};

const consumeStatusText = (status: string) => {
  const labels: Record<string, string> = {
    confirmed: "已确认",
    failed: "消费失败",
    pending: "等待消费"
  };
  return labels[status] ?? status;
};

const commentBlockReasonText = (reason?: string) => {
  const labels: Record<string, string> = {
    "Finding is missing file path and will be posted as a PR comment": "缺少文件路径，将作为 PR 总评评论回写。",
    "Finding is missing a valid line number and will be posted as a PR comment": "缺少有效行号，将作为 PR 总评评论回写。",
    "Finding file is not in the changed files list and will be posted as a PR comment": "该文件不在本次 PR 变更文件列表中，将作为 PR 总评评论回写。",
    "Deleted files will be posted as PR comments": "删除文件将作为 PR 总评评论回写。",
    "Finding marked as false positive and will not be published": "已标记为误报，不会回写。",
    "Finding marked as fixed and will not be published": "已标记为已修复，不会回写。",
    "Finding marked as ignored and will not be published": "已标记为忽略，不会回写。",
    "Finding is not actionable and will not be published": "该问题当前不可处理，不会回写。"
  };
  return reason ? labels[reason] ?? reason : "";
};

const commentTargetText = (targetType: string) => {
  const labels: Record<string, string> = {
    line: "行评论",
    pull_request: "PR 评论"
  };
  return labels[targetType] ?? targetType;
};

const commentPreviewKey = (item: GithubCommentPreview["items"][number]) =>
  item.findingId ?? `${item.targetType}-${item.file}-${item.publicationStatus ?? "draft"}`;

const sourceText = (source?: string) => {
  const labels: Record<string, string> = {
    manual_input: "手动输入",
    github_pr_picker: "GitHub PR 选择",
    existing_reused: "复用已有任务"
  };
  return source ? labels[source] ?? source : "手动输入";
};

const repositoryText = (owner?: string, repository?: string) => {
  if (!owner || !repository) {
    return "未配置";
  }
  return `${owner} / ${repository}`;
};

const writebackCheckStatusText = computed(() => {
  const labels: Record<string, string> = {
    ready: "配置匹配",
    repository_mismatch: "仓库不一致",
    repository_not_configured: "仓库未配置",
    token_missing: "Token 缺失",
    connection_failed: "连接异常"
  };
  return writebackCheck.value ? labels[writebackCheck.value.status] ?? writebackCheck.value.status : "";
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
  const labels: Record<string, string> = {
    not_required: "无需人工审查",
    pending: "待人工审查",
    approved: "人工通过",
    changes_requested: "要求修改",
    rejected: "已拒绝"
  };
  return labels[humanReviewStatus.value] ?? humanReviewStatus.value;
});

const humanReviewStatusClass = computed(() => {
  const classes: Record<string, string> = {
    not_required: "success",
    pending: "warning",
    approved: "success",
    changes_requested: "warning",
    rejected: "danger"
  };
  return classes[humanReviewStatus.value] ?? "pending";
});

const humanReviewActionText = (action: HumanReviewRequest["action"]) => {
  const labels: Record<HumanReviewRequest["action"], string> = {
    approve: "通过审查",
    changes_requested: "要求修改",
    reject: "拒绝"
  };
  return labels[action];
};

const findingFeedbackStatusText = (status?: FindingFeedbackStatus | string) => {
  const labels: Record<string, string> = {
    unreviewed: "未判定",
    valid: "有效",
    false_positive: "误报",
    fixed: "已修复",
    ignored: "忽略"
  };
  return status ? labels[String(status).toLowerCase()] ?? String(status) : labels.unreviewed;
};

const findingFeedbackStatusClass = (status?: FindingFeedbackStatus | string) => {
  const classes: Record<string, string> = {
    unreviewed: "pending",
    valid: "success",
    false_positive: "warning",
    fixed: "success",
    ignored: "pending"
  };
  return status ? classes[String(status).toLowerCase()] ?? "pending" : "pending";
};

const findingFeedbackPromptTitle = (status: FindingFeedbackStatus) => {
  const labels: Record<FindingFeedbackStatus, string> = {
    unreviewed: "重置判定",
    valid: "标记为有效",
    false_positive: "标记为误报",
    fixed: "标记为已修复",
    ignored: "标记为忽略"
  };
  return labels[status];
};

const publishStatusText = (status: string) => {
  const labels: Record<string, string> = {
    published: "已发布",
    failed: "失败",
    skipped: "跳过",
    already_published: "已发布，已跳过",
    downgraded_to_pr_comment: "已降级为 PR 评论"
  };
  return labels[status] ?? status;
};

const publicationBatchStatusText = (status: string) => {
  const labels: Record<string, string> = {
    completed: "完成",
    partial_failed: "部分失败",
    failed: "失败",
    skipped: "全部跳过",
    empty: "无审查发现"
  };
  return labels[status] ?? status;
};

const publicationBatchStatusClass = (status: string) => {
  const classes: Record<string, string> = {
    completed: "success",
    partial_failed: "warning",
    failed: "danger",
    skipped: "warning",
    empty: "pending"
  };
  return classes[status] ?? "pending";
};

const publicationItemStatusClass = (status: string) => {
  const classes: Record<string, string> = {
    published: "success",
    downgraded_to_pr_comment: "success",
    already_published: "skipped",
    skipped: "skipped",
    failed: "failed"
  };
  return classes[status] ?? "skipped";
};

const publicationMessageText = (message: string | undefined, status: string) => {
  const labels: Record<string, string> = {
    "GitHub comment published": "GitHub 评论已发布。",
    "GitHub comment already published": "该审查发现此前已经发布，本次已跳过。",
    "GitHub line comment could not be resolved; published as PR comment": "GitHub 行评论定位失败，已降级为 PR 总评评论。"
  };
  if (message && labels[message]) {
    return labels[message];
  }
  if (message) {
    return message;
  }
  const fallbackLabels: Record<string, string> = {
    published: "GitHub 评论已发布。",
    downgraded_to_pr_comment: "GitHub 行评论定位失败，已降级为 PR 总评评论。",
    already_published: "该审查发现此前已经发布，本次已跳过。",
    skipped: "本次未回写该审查发现。",
    failed: "GitHub 评论回写失败。"
  };
  return fallbackLabels[status] ?? status;
};

const normalizeStatusFields = (task: ReviewTaskDetail): ReviewTaskDetail => ({
  ...task,
  status: task.status as ReviewStatus,
  llmStatus: task.llmStatus as ReviewStatus,
  humanReviewRequired: Boolean(task.humanReviewRequired),
  humanReviewStatus: task.humanReviewStatus ?? "not_required",
  riskProfile: {
    score: task.riskProfile?.score ?? 0,
    level: (task.riskProfile?.level ?? "info") as RiskLevel,
    summary: task.riskProfile?.summary ?? "暂无风险画像数据。",
    recommendHumanReview: Boolean(task.riskProfile?.recommendHumanReview),
    humanReviewReason: task.riskProfile?.humanReviewReason ?? "可按常规流程推进。",
    signals: task.riskProfile?.signals ?? [],
    highRiskFiles: task.riskProfile?.highRiskFiles ?? []
  },
  prSummary: {
    overallRisk: task.prSummary?.overallRisk ?? task.riskProfile?.level ?? "info",
    summary: task.prSummary?.summary ?? "暂无 PR 总评数据。",
    mergeRecommendation: task.prSummary?.mergeRecommendation ?? "可按团队流程继续复核。",
    recommendMerge: Boolean(task.prSummary?.recommendMerge),
    humanReviewRequired: Boolean(task.prSummary?.humanReviewRequired),
    keyRisks: task.prSummary?.keyRisks ?? [],
    focusFiles: task.prSummary?.focusFiles ?? [],
    githubCommentBody: task.prSummary?.githubCommentBody ?? ""
  },
  llm: {
    ...task.llm,
    status: task.llm.status as ReviewStatus,
    promptTokens: task.llm.promptTokens ?? 0,
    completionTokens: task.llm.completionTokens ?? 0,
    totalTokens: task.llm.totalTokens ?? 0,
    estimatedCost: task.llm.estimatedCost ?? ""
  },
  chunkedReview: {
    enabled: Boolean(task.chunkedReview?.enabled),
    chunkCount: task.chunkedReview?.chunkCount ?? 0,
    aggregateRisk: task.chunkedReview?.aggregateRisk ?? "info",
    aggregateFindings: task.chunkedReview?.aggregateFindings ?? 0,
    failedChunks: task.chunkedReview?.failedChunks ?? 0,
    reasons: task.chunkedReview?.reasons ?? []
  }
});

const normalizeTimelineItem = (item: TimelineItem): TimelineItem => ({
  ...item,
  status: item.status as TimelineItem["status"]
});

const mergeLatestTimeline = (timeline: TimelineItem[], latestTimeline?: TimelineItem): TimelineItem[] => {
  if (!latestTimeline) {
    return timeline;
  }
  const latest = normalizeTimelineItem(latestTimeline);
  const normalizedTimeline = timeline.map((item) =>
    latest.status === "current" && item.status === "current" && item.label !== latest.label
      ? { ...item, status: "done" as TimelineItem["status"] }
      : item
  );
  const existingIndex = normalizedTimeline.findIndex((item) => item.label === latest.label && item.time === latest.time);
  if (existingIndex >= 0) {
    return normalizedTimeline.map((item, index) => (index === existingIndex ? latest : item));
  }
  return [...normalizedTimeline, latest];
};

const applyStatusSnapshot = (status: ReviewTaskStatus) => {
  if (!selectedTask.value) {
    return;
  }
  const normalizedStatus = status.status as ReviewStatus;
  const normalizedLlmStatus = status.llmStatus as ReviewStatus;
  const normalizedRiskLevel = status.riskLevel as RiskLevel;
  selectedTask.value = {
    ...selectedTask.value,
    status: normalizedStatus,
    riskLevel: normalizedRiskLevel,
    llmStatus: normalizedLlmStatus,
    duration: status.duration,
    failureCategory: status.failureCategory,
    failureReason: status.failureReason,
    failureSuggestion: status.failureSuggestion,
    humanReviewRequired: status.humanReviewRequired,
    humanReviewStatus: status.humanReviewStatus,
    humanReviewNote: status.humanReviewNote,
    humanReviewBy: status.humanReviewBy,
    humanReviewedAt: status.humanReviewedAt,
    timeline: mergeLatestTimeline(selectedTask.value.timeline, status.latestTimeline),
    llm: {
      ...selectedTask.value.llm,
      status: normalizedLlmStatus,
      duration: status.duration,
      riskLevel: normalizedRiskLevel
    }
  };
};

const formatRefreshTime = () =>
  new Intl.DateTimeFormat("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date());

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

const stopPolling = () => {
  if (!pollTimer) {
    return;
  }
  clearTimeout(pollTimer);
  pollTimer = undefined;
};

const cleanupPolling = () => {
  stopPolling();
};

const startPolling = () => {
  if (pollTimer || !shouldPollTask.value) {
    return;
  }
  if (pollFailureCount.value >= MAX_POLL_FAILURES) {
    stopPolling();
    return;
  }
  pollTimer = setTimeout(() => {
    pollTimer = undefined;
    void pollReviewStatus();
  }, currentPollIntervalMs.value);
};

const syncPolling = () => {
  if (shouldPollTask.value) {
    startPolling();
  } else {
    stopPolling();
  }
};

const loadDetail = async (options: LoadDetailOptions = {}) => {
  const id = Number(route.params.id);
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
    githubCommentPublishResult.value = null;
  }
  try {
    const task = normalizeStatusFields(await fetchReviewDetail(id));
    selectedTask.value = task;
    pollErrorMessage.value = "";
    pollFailureCount.value = 0;
    lastRefreshedAt.value = formatRefreshTime();
    if (isTerminalReviewStatus(task.status)) {
      await Promise.all([
        loadGithubCommentPreview(id),
        loadGithubCommentPublicationHistory(id)
      ]);
    } else {
      previewError.value = "";
      githubCommentPreview.value = null;
      historyError.value = "";
      githubCommentPublicationHistory.value = null;
    }
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
      if (pollFailureCount.value >= MAX_POLL_FAILURES) {
        stopPolling();
        pollErrorMessage.value = `自动刷新连续失败 ${MAX_POLL_FAILURES} 次，已暂停。请手动刷新。`;
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
  const id = Number(route.params.id);
  if (!Number.isFinite(id)) {
    return;
  }
  if (silentRefreshing.value) {
    return;
  }

  silentRefreshing.value = true;
  try {
    const status = await fetchReviewStatus(id);
    applyStatusSnapshot(status);
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
    if (pollFailureCount.value >= MAX_POLL_FAILURES) {
      stopPolling();
      pollErrorMessage.value = `Automatic refresh failed ${MAX_POLL_FAILURES} times and has paused. Please refresh manually.`;
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
  void loadDetail({ silent: true, resetPublishResult: false });
};

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

  publishingComments.value = true;
  try {
    githubCommentPublishResult.value = await publishGithubComments(selectedTask.value.id);
    const result = githubCommentPublishResult.value;
    if (result.failedCount > 0) {
      ElMessage.warning(`GitHub 评论回写完成：成功 ${result.succeededCount} 条，失败 ${result.failedCount} 条`);
    } else {
      ElMessage.success(`GitHub 评论回写成功：${result.succeededCount} 条`);
    }
    await Promise.all([
      loadGithubCommentPreview(selectedTask.value.id),
      loadGithubCommentPublicationHistory(selectedTask.value.id)
    ]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    publishingComments.value = false;
  }
};

const submitHumanReviewDecision = async (action: HumanReviewRequest["action"]) => {
  if (!selectedTask.value || !canSubmitHumanReview.value || submittingHumanReview.value) {
    return;
  }
  try {
    const promptResult = await ElMessageBox.prompt(
      "请输入人工审查意见",
      humanReviewActionText(action),
      {
        confirmButtonText: "提交",
        cancelButtonText: "取消",
        inputType: "textarea",
        inputPlaceholder: action === "approve" ? "可选：记录通过原因" : "请说明需要修改或拒绝的原因",
        inputValidator: (value) => {
          if (action === "approve") {
            return true;
          }
          return Boolean(value?.trim()) || "请填写审查意见";
        }
      }
    );
    submittingHumanReview.value = true;
    const response = await submitHumanReview(selectedTask.value.id, {
      action,
      note: promptResult.value?.trim()
    });
    selectedTask.value = {
      ...selectedTask.value,
      status: response.status as ReviewStatus,
      humanReviewRequired: response.humanReviewRequired,
      humanReviewStatus: response.humanReviewStatus,
      humanReviewNote: response.humanReviewNote,
      humanReviewBy: response.humanReviewBy,
      humanReviewedAt: response.humanReviewedAt
    };
    ElMessage.success(humanReviewActionText(action));
    await loadDetail({ silent: true, resetPublishResult: true, force: true });
  } catch (error) {
    if (error === "cancel" || error === "close") {
      return;
    }
    ElMessage.error(getErrorMessage(error, "人工审查提交失败"));
  } finally {
    submittingHumanReview.value = false;
  }
};

const applyFindingFeedback = (response: FindingFeedbackResponse) => {
  if (!selectedTask.value) {
    return;
  }
  selectedTask.value = {
    ...selectedTask.value,
    findings: selectedTask.value.findings.map((finding) =>
      finding.id === response.findingId
        ? {
            ...finding,
            feedbackStatus: response.feedbackStatus,
            feedbackNote: response.feedbackNote,
            feedbackBy: response.feedbackBy,
            feedbackAt: response.feedbackAt
          }
        : finding
    )
  };
};

const submitFindingFeedback = async (findingId: number, status: FindingFeedbackStatus) => {
  if (!selectedTask.value || !canManage.value || feedbackSavingId.value) {
    return;
  }
  try {
    const promptResult = await ElMessageBox.prompt(
      "请输入判定备注",
      findingFeedbackPromptTitle(status),
      {
        confirmButtonText: "提交",
        cancelButtonText: "取消",
        inputType: "textarea",
        inputPlaceholder: status === "valid" || status === "fixed" ? "可选：记录确认依据" : "请说明判定原因",
        inputValidator: (value) => {
          if (status === "valid" || status === "fixed") {
            return true;
          }
          return Boolean(value?.trim()) || "请填写判定原因";
        }
      }
    );
    feedbackSavingId.value = findingId;
    const response = await updateFindingFeedback(selectedTask.value.id, findingId, {
      status,
      note: promptResult.value?.trim()
    });
    applyFindingFeedback(response);
    githubCommentPublishResult.value = null;
    if (isTerminalTask.value) {
      await loadGithubCommentPreview(selectedTask.value.id);
    }
    ElMessage.success(findingFeedbackPromptTitle(status));
  } catch (error) {
    if (error === "cancel" || error === "close") {
      return;
    }
    ElMessage.error(getErrorMessage(error, "判定提交失败"));
  } finally {
    feedbackSavingId.value = null;
  }
};

const confirmRetryReview = async () => {
  if (!canManage.value || !selectedTask.value || !canRetryTask.value || retryingTask.value) {
    return;
  }

  try {
    const failureText = failureReason.value ? `\n\n失败原因：${failureReason.value}` : "";
    await ElMessageBox.confirm(
      `确认将 PR #${selectedTask.value.prNumber} 重新加入审查队列？${failureText}`,
      "确认重试审查任务",
      {
        confirmButtonText: "确认重试",
        cancelButtonText: "取消",
        type: "warning"
      }
    );
  } catch {
    return;
  }

  retryingTask.value = true;
  try {
    const response = await retryReview(selectedTask.value.id);
    ElMessage.success(response.message || "审查任务已重新入队");
    githubCommentPreview.value = null;
    githubCommentPublicationHistory.value = null;
    githubCommentPublishResult.value = null;
    pollFailureCount.value = 0;
    pollErrorMessage.value = "";
    await loadDetail({ silent: true, resetPublishResult: true });
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    retryingTask.value = false;
  }
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
