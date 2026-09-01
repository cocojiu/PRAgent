<template>
  <aside class="detail-side">
    <article class="dashboard-card">
      <h2>任务时间线</h2>
      <div v-if="!timelineLoaded" class="lazy-section-actions">
        <el-button type="primary" plain :loading="timelineLoading" @click="$emit('timelineLoad')">
          <RefreshCw :size="16" />
          加载时间线
        </el-button>
      </div>
      <ol v-else-if="timeline.length" class="timeline">
        <li v-for="item in timeline" :key="`${item.label}-${item.time}`" :class="`timeline-${item.status}`">
          <span></span>
          <b>{{ item.label }}</b>
          <em>{{ item.time }}</em>
        </li>
      </ol>
      <el-empty v-else description="暂无任务时间线" />
    </article>

    <article class="dashboard-card side-card">
      <h2>LLM 状态</h2>
      <dl>
        <dt>执行状态</dt><dd><span :class="`status-pill ${statusClass(task.llm.status)}`">{{ statusText(task.llm.status) }}</span></dd>
        <dt>模型</dt><dd>{{ llmModelText }}</dd>
        <dt>解析状态</dt><dd><span :class="`status-pill ${llmParseStatusClass}`">{{ llmParseStatusText }}</span></dd>
        <dt>规则兜底</dt><dd>{{ ruleFallbackText(task) }}</dd>
        <dt>耗时</dt><dd>{{ llmDurationText }}</dd>
        <dt>风险级别</dt><dd>{{ riskText(task.llm.riskLevel) }}</dd>
        <dt>Token 用量</dt><dd>{{ llmTokenUsageText }}</dd>
        <dt>成本估算</dt><dd>{{ llmCostText }}</dd>
        <dt>分片审查</dt><dd>{{ task.chunkedReview.enabled ? "已启用" : "未启用" }}</dd>
        <dt v-if="task.chunkedReview.enabled">分片数量</dt><dd v-if="task.chunkedReview.enabled">{{ task.chunkedReview.chunkCount }}</dd>
        <dt v-if="task.chunkedReview.enabled">聚合风险</dt><dd v-if="task.chunkedReview.enabled">{{ chunkAggregateRiskText(task.chunkedReview.aggregateRisk) }}</dd>
        <dt v-if="task.chunkedReview.enabled">聚合发现</dt><dd v-if="task.chunkedReview.enabled">{{ task.chunkedReview.aggregateFindings }}</dd>
        <dt v-if="task.chunkedReview.enabled && task.chunkedReview.failedChunks > 0">规则补位分片</dt><dd v-if="task.chunkedReview.enabled && task.chunkedReview.failedChunks > 0">{{ task.chunkedReview.failedChunks }}</dd>
        <dt v-if="task.chunkedReview.enabled && task.chunkedReview.reasons.length">分片原因</dt>
        <dd v-if="task.chunkedReview.enabled && task.chunkedReview.reasons.length" class="chunk-reasons">
          <span v-for="reason in task.chunkedReview.reasons" :key="reason">{{ chunkReasonText(reason) }}</span>
        </dd>
        <dt v-if="task.llm.fallbackReason">兜底原因</dt><dd v-if="task.llm.fallbackReason" class="status-reason">{{ task.llm.fallbackReason }}</dd>
        <dt v-if="task.llm.promptSummary">Prompt 摘要</dt><dd v-if="task.llm.promptSummary" class="status-reason">{{ task.llm.promptSummary }}</dd>
        <dt v-if="statusReason">原因</dt><dd v-if="statusReason" class="status-reason">{{ statusReason }}</dd>
      </dl>
    </article>

    <article class="dashboard-card side-card">
      <h2>RabbitMQ</h2>
      <dl>
        <dt>投递次数</dt><dd>{{ task.rabbitMq.deliveryCount }}</dd>
        <dt>重试次数</dt><dd>{{ task.rabbitMq.retryCount }}</dd>
        <dt>消费状态</dt><dd><span class="status-pill success">{{ consumeStatusText(task.rabbitMq.consumeStatus) }}</span></dd>
      </dl>
    </article>
  </aside>
</template>

<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import type { ReviewStatus, ReviewTaskDetail, RiskLevel, TimelineItemViewModel } from "@/types";

defineProps<{
  task: ReviewTaskDetail;
  timeline: TimelineItemViewModel[];
  timelineLoaded: boolean;
  timelineLoading: boolean;
  llmModelText: string;
  llmParseStatusText: string;
  llmParseStatusClass: string;
  llmDurationText: string;
  llmTokenUsageText: string;
  llmCostText: string;
  statusReason: string;
  statusText: (status: ReviewStatus) => string;
  statusClass: (status: ReviewStatus) => string;
  riskText: (risk: RiskLevel) => string;
  chunkAggregateRiskText: (risk?: RiskLevel | string) => string;
  chunkReasonText: (reason: string) => string;
  consumeStatusText: (status: string) => string;
}>();

defineEmits<{
  timelineLoad: [];
}>();

const ruleFallbackText = (task: ReviewTaskDetail) => {
  if (task.llm.status === "fallback") {
    return "已启用";
  }
  if (task.llm.parseStatus === "partial_fallback" || task.chunkedReview.failedChunks > 0) {
    return "分片规则兜底";
  }
  return "未触发";
};
</script>
