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
          <el-tooltip content="任务重试接口尚未接入">
            <span>
              <el-button type="primary" size="large" disabled>
                <RefreshCw :size="16" />
                重试
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <section class="detail-kpi-grid">
        <div class="detail-kpi">
          <div class="metric-icon metric-icon--blue"><Archive :size="26" /></div>
          <div><p>仓库</p><strong>{{ selectedTask.repository }}</strong><span>{{ selectedTask.organization }}</span></div>
        </div>
        <div class="detail-kpi">
          <div class="metric-icon metric-icon--purple"><GitBranch :size="26" /></div>
          <div><p>Commit</p><strong>{{ selectedTask.commit }} <Copy :size="15" /></strong><span>{{ selectedTask.branch }}</span></div>
        </div>
        <div class="detail-kpi">
          <div class="metric-icon metric-icon--green"><Clock :size="26" /></div>
          <div><p>耗时</p><strong>{{ selectedTask.duration }}</strong><span>开始于 {{ reviewTimeline[0]?.time ?? "-" }}</span></div>
        </div>
        <div class="detail-kpi">
          <div class="metric-icon metric-icon--orange"><MessagesSquare :size="26" /></div>
          <div><p>审查发现</p><strong>{{ reviewFindings.length }} 条</strong><span>{{ changedFiles.length }} 个变更文件</span></div>
        </div>
      </section>

      <div class="detail-layout">
        <main class="detail-main">
          <article class="dashboard-card summary-card">
            <h2>审查摘要</h2>
            <p>
              本次任务当前状态为 {{ statusText(selectedTask.status) }}，LLM 状态为
              {{ statusText(selectedTask.llm.status) }}。共发现 {{ reviewFindings.length }} 条问题，下面按严重级别、文件、行号和修复建议展开。
            </p>
            <div class="summary-stats">
              <div class="summary-stat high"><span>高风险</span><strong>{{ findingCounts.high + findingCounts.critical }}</strong></div>
              <div class="summary-stat medium"><span>中风险</span><strong>{{ findingCounts.medium }}</strong></div>
              <div class="summary-stat low"><span>低风险</span><strong>{{ findingCounts.low }}</strong></div>
              <div class="summary-stat info"><span>提示</span><strong>{{ findingCounts.info }}</strong></div>
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
              </section>
            </div>
            <el-empty v-else description="暂无审查问题" />
          </article>

          <article class="dashboard-card github-preview-card">
            <div class="card-title-row">
              <h2>GitHub 评论预览</h2>
              <el-button
                type="primary"
                :disabled="!githubCommentPreview?.commentableCount"
                :loading="publishingComments"
                @click="confirmPublishGithubComments"
              >
                <Github :size="16" />
                回写到 GitHub
              </el-button>
            </div>
            <el-alert
              v-if="previewError"
              class="preview-alert"
              type="warning"
              :title="previewError"
              show-icon
              :closable="false"
            />
            <template v-if="githubCommentPreview">
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
                  :key="item.findingId"
                  :class="['comment-preview-item', { blocked: !item.commentable }]"
                >
                  <div class="comment-preview-head">
                    <span :class="`risk-pill ${item.severity}`">{{ riskText(item.severity) }}</span>
                    <code>{{ item.file }}</code>
                    <span class="line-badge">L{{ item.line ?? "-" }}</span>
                    <span class="count-badge">{{ commentTargetText(item.targetType) }}</span>
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
                  :key="`${item.findingId}-${item.status}`"
                  :class="['comment-result-item', publicationItemStatusClass(item.status)]"
                >
                  <div>
                    <strong>{{ item.file }}{{ item.line ? `:L${item.line}` : "" }}</strong>
                    <span>{{ publishStatusText(item.status) }}</span>
                  </div>
                  <p>{{ publicationMessageText(item.message, item.status) }}</p>
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
                      <a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">查看 GitHub 评论</a>
                    </div>
                  </div>
                </section>
              </div>
              <el-empty v-else-if="!historyError" description="暂无回写历史" />
            </template>
            <el-empty v-else-if="!previewError" description="评论预览加载中" />
          </article>

          <article class="dashboard-card">
            <h2>缺失测试</h2>
            <el-table :data="missingTests" class="rg-table" size="large" aria-label="缺失测试列表">
              <el-table-column prop="file" label="文件" min-width="320" />
              <el-table-column prop="method" label="涉及类/方法" min-width="220" />
              <el-table-column prop="type" label="缺失测试类型" width="160" />
              <el-table-column prop="suggestion" label="建议" min-width="280" />
              <template #empty>
                <el-empty description="暂无缺失测试建议" />
              </template>
            </el-table>
          </article>

          <article class="dashboard-card">
            <h2>变更文件</h2>
            <el-table :data="changedFilesWithFindingCounts" class="rg-table" size="large" aria-label="变更文件列表">
              <el-table-column label="文件路径" min-width="420">
                <template #default="{ row }">
                  <div class="changed-file-cell">
                    <code>{{ row.path }}</code>
                    <span v-if="row.findingCount" class="count-badge warning">{{ row.findingCount }} 条问题</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="变更类型" width="140">
                <template #default="{ row }">
                  <span class="file-type">{{ changeTypeText(row.changeType) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="变更行数" width="160">
                <template #default="{ row }">
                  <span class="additions">+{{ row.additions }}</span>
                  <span class="deletions"> -{{ row.deletions }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无变更文件" />
              </template>
            </el-table>
          </article>
        </main>

        <aside class="detail-side">
          <article class="dashboard-card">
            <h2>任务时间线</h2>
            <ol class="timeline">
              <li v-for="item in localizedTimeline" :key="`${item.label}-${item.time}`" :class="`timeline-${item.status}`">
                <span></span>
                <b>{{ item.label }}</b>
                <em>{{ item.time }}</em>
              </li>
            </ol>
          </article>

          <article class="dashboard-card side-card">
            <h2>LLM 状态</h2>
            <dl>
              <dt>执行状态</dt><dd><span :class="`status-pill ${statusClass(selectedTask.llm.status)}`">{{ statusText(selectedTask.llm.status) }}</span></dd>
              <dt>规则兜底</dt><dd>{{ selectedTask.llm.status === "fallback" ? "已启用" : "未触发" }}</dd>
              <dt>耗时</dt><dd>{{ selectedTask.llm.duration }}</dd>
              <dt>风险级别</dt><dd>{{ riskText(selectedTask.llm.riskLevel) }}</dd>
              <dt v-if="statusReason">原因</dt><dd v-if="statusReason" class="status-reason">{{ statusReason }}</dd>
            </dl>
          </article>

          <article class="dashboard-card side-card">
            <h2>RabbitMQ</h2>
            <dl>
              <dt>投递次数</dt><dd>{{ selectedTask.rabbitMq.deliveryCount }}</dd>
              <dt>重试次数</dt><dd>{{ selectedTask.rabbitMq.retryCount }}</dd>
              <dt>消费状态</dt><dd><span class="status-pill success">{{ consumeStatusText(selectedTask.rabbitMq.consumeStatus) }}</span></dd>
            </dl>
          </article>
        </aside>
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
import { ElMessage, ElMessageBox } from "element-plus";
import { Archive, ArrowLeft, Clock, Copy, ExternalLink, GitBranch, Github, MessagesSquare, RefreshCw } from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import {
  fetchGithubCommentPreview,
  fetchGithubCommentPublicationHistory,
  fetchReviewDetail,
  publishGithubComments
} from "@/api/reviews";
import type {
  ChangedFile,
  GithubCommentPreview,
  GithubCommentPublicationBatch,
  GithubCommentPublicationHistory,
  GithubCommentPublish,
  ReviewStatus,
  ReviewTaskDetail,
  RiskLevel,
  TimelineItem
} from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };
type LoadDetailOptions = { silent?: boolean; resetPublishResult?: boolean };

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_FAILURES = 3;
const MAX_POLL_INTERVAL_MS = 30000;

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const silentRefreshing = ref(false);
const publishingComments = ref(false);
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
let pollTimer: ReturnType<typeof setTimeout> | undefined;

const reviewFindings = computed(() => selectedTask.value?.findings ?? []);
const missingTests = computed(() => selectedTask.value?.missingTests ?? []);
const changedFiles = computed(() => selectedTask.value?.changedFiles ?? []);
const reviewTimeline = computed(() => selectedTask.value?.timeline ?? []);
const emptyDescription = computed(() => (errorMessage.value ? "审查详情加载失败" : "未找到审查任务"));
const isTerminalTask = computed(() => {
  const status = selectedTask.value?.status;
  return status === "completed" || status === "failed";
});
const shouldPollTask = computed(() => Boolean(selectedTask.value && !isTerminalTask.value));
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

const changeTypeText = (type: ChangedFile["changeType"]) => {
  const labels: Record<ChangedFile["changeType"], string> = {
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
    "Deleted files will be posted as PR comments": "删除文件将作为 PR 总评评论回写。"
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
  llm: {
    ...task.llm,
    status: task.llm.status as ReviewStatus
  }
});

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
    previewError.value = error instanceof Error ? error.message : "GitHub 评论预览加载失败";
  }
};

const loadGithubCommentPublicationHistory = async (id: number) => {
  historyError.value = "";
  try {
    githubCommentPublicationHistory.value = await fetchGithubCommentPublicationHistory(id);
  } catch (error) {
    githubCommentPublicationHistory.value = null;
    historyError.value = error instanceof Error ? error.message : "GitHub 回写历史加载失败";
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
    void loadDetail({ silent: true, resetPublishResult: false });
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

  if (options.silent && silentRefreshing.value) {
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
    if (task.status === "completed" || task.status === "failed") {
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
    errorMessage.value = error instanceof Error ? error.message : "审查详情加载失败";
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

const refreshDetail = () => {
  pollFailureCount.value = 0;
  pollErrorMessage.value = "";
  void loadDetail({ silent: true, resetPublishResult: false });
};

const confirmPublishGithubComments = async () => {
  if (!selectedTask.value || !githubCommentPreview.value?.commentableCount) {
    return;
  }

  try {
    await ElMessageBox.confirm(
      `将向 GitHub PR #${selectedTask.value.prNumber} 回写 ${githubCommentPreview.value.commentableCount} 条行评论。确认继续？`,
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
    ElMessage.error(error instanceof Error ? error.message : "GitHub 评论回写失败");
  } finally {
    publishingComments.value = false;
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
