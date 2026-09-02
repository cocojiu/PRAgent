<template>
  <article class="dashboard-card attempt-comparison-card">
    <div class="attempt-comparison-head">
      <div>
        <span class="card-eyebrow">审查历史</span>
        <h2>跨次审查差异</h2>
      </div>
      <span v-if="comparison" :class="`status-pill ${comparison.comparable ? 'success' : 'warning'}`">
        {{ comparison.comparable ? "可比较" : "不可直接比较" }}
      </span>
    </div>

    <div v-if="attempts.length" class="attempt-timeline" aria-label="审查尝试时间线">
      <span v-for="attempt in attempts.slice(0, 5)" :key="attempt.id" :class="['attempt-dot', { current: attempt.current }]">
        #{{ attempt.attemptNo }} · {{ attempt.status }}
      </span>
      <span v-if="attempts.length > 5" class="attempt-dot more">+{{ attempts.length - 5 }} 次</span>
    </div>

    <el-alert v-if="error" type="warning" :title="error" show-icon :closable="false" />
    <el-skeleton v-else-if="loading" :rows="3" animated />
    <p v-else-if="!comparison" class="attempt-comparison-empty">
      当前还没有可比较的成功审查尝试。
    </p>
    <template v-else>
      <el-alert
        v-if="!comparison.comparable"
        type="warning"
        :title="`本次结果未与上一版本直接比较：${comparabilityReason(comparison.comparabilityReason)}`"
        show-icon
        :closable="false"
      />
      <p class="attempt-comparison-meta">
        基线 #{{ comparison.baselineAttemptId ?? "—" }} · 候选 #{{ comparison.candidateAttemptId }}
        <span v-if="comparison.baselineCommitSha"> · {{ shortSha(comparison.baselineCommitSha) }} → </span>
        <span v-if="comparison.candidateCommitSha">{{ shortSha(comparison.candidateCommitSha) }}</span>
      </p>

      <div class="comparison-stats">
        <span class="comparison-stat new">新增 <strong>{{ comparison.summary.newCount }}</strong></span>
        <span class="comparison-stat regressed">回归 <strong>{{ comparison.summary.regressedCount }}</strong></span>
        <span class="comparison-stat persisting">仍存在 <strong>{{ comparison.summary.persistingCount }}</strong></span>
        <span class="comparison-stat unmatched">未匹配 <strong>{{ comparison.summary.unmatchedCount }}</strong></span>
      </div>

      <div v-if="visibleFindings.length" class="finding-diff-list">
        <div v-for="item in visibleFindings" :key="`${item.status}-${item.id}`" class="finding-diff-item">
          <div class="finding-diff-item-head">
            <span :class="`comparison-status ${statusClass(item.status)}`">{{ statusText(item.status) }}</span>
            <code>{{ item.file || "未定位文件" }}{{ item.line ? `:${item.line}` : "" }}</code>
          </div>
          <strong>{{ item.message }}</strong>
          <small v-if="item.reason">{{ reasonText(item.reason) }}</small>
        </div>
      </div>
      <p v-else class="attempt-comparison-empty">当前页没有新的差异项。</p>

      <details v-if="resolvedFindings.length" class="resolved-findings">
        <summary>已修复 {{ comparison.summary.resolvedCount }} 条（展开追溯）</summary>
        <div v-for="item in resolvedFindings" :key="`resolved-${item.id}`" class="finding-diff-item resolved">
          <div class="finding-diff-item-head">
            <span class="comparison-status resolved">已修复</span>
            <code>{{ item.file || "未定位文件" }}{{ item.line ? `:${item.line}` : "" }}</code>
          </div>
          <strong>{{ item.message }}</strong>
        </div>
      </details>

      <el-pagination
        v-if="comparison.findings.hasMore || page > 1"
        class="comparison-pagination"
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :total="comparison.findings.total"
        @current-change="$emit('page-change', $event)"
      />
    </template>
  </article>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { ReviewAttemptComparison, ReviewExecutionAttempt } from "@/types";

const props = defineProps<{
  attempts: ReviewExecutionAttempt[];
  comparison: ReviewAttemptComparison | null;
  loading: boolean;
  error: string;
  page: number;
  pageSize: number;
}>();

defineEmits<{
  "page-change": [page: number];
}>();

const resolvedFindings = computed(() =>
  props.comparison?.findings.items.filter((item) => item.status === "RESOLVED") ?? []
);
const visibleFindings = computed(() =>
  props.comparison?.findings.items.filter((item) => item.status !== "RESOLVED") ?? []
);

const statusText = (status: string) => ({
  NEW: "新增",
  REGRESSED: "回归",
  PERSISTING: "仍存在",
  UNMATCHED: "未匹配",
  RESOLVED: "已修复"
}[status] ?? status);

const statusClass = (status: string) => status.toLowerCase();

const reasonText = (reason: string) => ({
  STABLE_FINGERPRINT_MATCH: "稳定指纹匹配",
  NO_MATCHING_PREVIOUS_FINGERPRINT: "上一版本没有相同指纹",
  REAPPEARED_AFTER_RESOLUTION: "已修复问题重新出现",
  LOCATION_CHANGED_OR_CROSS_FILE: "位置或文件发生变化，未自动迁移结论",
  DUPLICATE_FINGERPRINT: "本次结果出现重复指纹",
  MISSING_FINGERPRINT: "缺少可验证指纹"
}[reason] ?? reason);

const comparabilityReason = (reason: string) => ({
  STRATEGY_VERSION_CHANGED: "规则、prompt、模型或 detector 版本发生变化",
  NO_PREVIOUS_SUCCESSFUL_ATTEMPT: "没有上一轮成功结果"
}[reason] ?? reason);

const shortSha = (sha: string) => sha.length > 12 ? `${sha.slice(0, 7)}…${sha.slice(-4)}` : sha;
</script>
