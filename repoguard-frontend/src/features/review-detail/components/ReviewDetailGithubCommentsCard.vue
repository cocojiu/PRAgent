<template>
  <article v-loading="previewLoading || historyLoading" class="dashboard-card github-preview-card">
    <div class="card-title-row">
      <h2>GitHub 评论预览</h2>
      <el-button
        v-if="!githubCommentPreview"
        type="primary"
        plain
        :disabled="!canLoadGithubComments"
        :loading="previewLoading || historyLoading"
        @click="$emit('loadPreview')"
      >
        <Github :size="16" />
        加载预览
      </el-button>
      <el-button
        v-else
        type="primary"
        :disabled="!canManage || !canPublishGithubComments"
        :loading="publishingComments"
        @click="$emit('publish')"
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
      <div class="comment-preview-controls">
        <span class="count-badge">预览项：{{ githubCommentPreview.itemTotal }}</span>
        <el-switch
          :model-value="previewCommentableOnly"
          active-text="仅可回写"
          @change="$emit('previewCommentableOnlyChange', Boolean($event))"
        />
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
      <el-pagination
        v-if="githubCommentPreview.itemTotal > githubCommentPreview.pageSize"
        class="detail-pagination"
        layout="prev, pager, next"
        :current-page="githubCommentPreview.page"
        :page-size="githubCommentPreview.pageSize"
        :total="githubCommentPreview.itemTotal"
        @current-change="$emit('previewPageChange', $event)"
      />
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
      <el-pagination
        v-if="historyTotal > historyPageSize"
        class="detail-pagination"
        layout="prev, pager, next"
        :current-page="historyPage"
        :page-size="historyPageSize"
        :total="historyTotal"
        @current-change="$emit('historyPageChange', $event)"
      />
      <el-empty v-else-if="!historyError" description="暂无回写历史" />
    </template>
    <el-empty v-else-if="!previewError" description="评论预览尚未加载">
      <el-button
        type="primary"
        plain
        :disabled="!canLoadGithubComments"
        :loading="previewLoading || historyLoading"
        @click="$emit('loadPreview')"
      >
        加载评论预览
      </el-button>
    </el-empty>
  </article>
</template>

<script setup lang="ts">
import { Github } from "lucide-vue-next";
import { RouterLink } from "vue-router";
import type {
  FindingFeedbackStatus,
  GithubCommentPreview,
  GithubCommentPreviewItem,
  GithubCommentPublicationBatch,
  GithubCommentPublish,
  GithubCommentWritebackCheck,
  RiskLevel
} from "@/types";

defineProps<{
  canManage: boolean;
  canLoadGithubComments: boolean;
  canPublishGithubComments: boolean;
  previewLoading: boolean;
  historyLoading: boolean;
  publishingComments: boolean;
  humanReviewPublishBlockReason: string;
  previewError: string;
  historyError: string;
  historyPage: number;
  historyPageSize: number;
  historyTotal: number;
  previewCommentableOnly: boolean;
  githubCommentPreview: GithubCommentPreview | null;
  githubCommentPublishResult: GithubCommentPublish | null;
  writebackCheck?: GithubCommentWritebackCheck;
  writebackCheckStatusClass: string;
  writebackCheckStatusText: string;
  publishedCommentCount: number;
  publicationHistoryBatches: GithubCommentPublicationBatch[];
  repositoryText: (owner?: string, repository?: string) => string;
  commentPreviewKey: (item: GithubCommentPreviewItem) => string | number;
  riskText: (risk: RiskLevel) => string;
  commentTargetText: (targetType: string) => string;
  findingFeedbackStatusClass: (status?: FindingFeedbackStatus | string) => string;
  findingFeedbackStatusText: (status?: FindingFeedbackStatus | string) => string;
  commentBlockReasonText: (reason?: string) => string;
  publicationItemStatusClass: (status: string) => string;
  publishStatusText: (status: string) => string;
  publicationMessageText: (message: string | undefined, status: string) => string;
  publicationBatchStatusClass: (status: string) => string;
  publicationBatchStatusText: (status: string) => string;
}>();

defineEmits<{
  loadPreview: [];
  historyPageChange: [page: number];
  previewCommentableOnlyChange: [value: boolean];
  previewPageChange: [page: number];
  publish: [];
}>();
</script>
