<template>
  <article class="dashboard-card summary-card">
    <h2>审查摘要</h2>
    <p>
      本次任务当前状态为 {{ statusText(task.status) }}，LLM 状态为
      {{ statusText(task.llm.status) }}。共发现 {{ findingCount }} 条问题，下面按严重级别、文件、行号和修复建议展开。
    </p>
    <div class="summary-stats">
      <div class="summary-stat high"><span>高风险</span><strong>{{ findingCounts.high + findingCounts.critical }}</strong></div>
      <div class="summary-stat medium"><span>中风险</span><strong>{{ findingCounts.medium }}</strong></div>
      <div class="summary-stat low"><span>低风险</span><strong>{{ findingCounts.low }}</strong></div>
      <div class="summary-stat info"><span>提示</span><strong>{{ findingCounts.info }}</strong></div>
    </div>
    <div v-if="riskProfile" class="risk-profile">
      <div class="risk-profile-head">
        <div>
          <span class="risk-profile-eyebrow">PR 风险画像</span>
          <strong>{{ riskProfile.score }}/100</strong>
        </div>
        <span :class="`risk-pill ${riskProfile.level}`">{{ riskText(riskProfile.level) }}</span>
      </div>
      <p>{{ riskProfile.summary }}</p>
      <p class="risk-profile-review">{{ riskProfile.humanReviewReason }}</p>
      <div class="risk-profile-signals">
        <span v-for="signal in riskProfile.signals" :key="signal">{{ signal }}</span>
      </div>
      <div v-if="riskProfile.highRiskFiles.length" class="risk-file-list">
        <div v-for="file in riskProfile.highRiskFiles" :key="file.file" class="risk-file-item">
          <div>
            <code>{{ file.file }}</code>
            <small>{{ changeTypeText(file.changeType) }} · +{{ file.additions }} -{{ file.deletions }} · {{ file.findingCount }} 条问题</small>
          </div>
          <div class="risk-file-meta">
            <span>{{ file.score }}</span>
            <em v-for="reason in file.reasons" :key="`${file.file}-${reason}`">{{ reason }}</em>
          </div>
        </div>
      </div>
    </div>
    <div v-if="task.prSummary" class="pr-summary">
      <div class="pr-summary-head">
        <span class="risk-profile-eyebrow">PR 总评</span>
        <span :class="`status-pill ${task.prSummary.recommendMerge ? 'success' : 'warning'}`">
          {{ task.prSummary.recommendMerge ? "可按流程合并" : "建议复核后合并" }}
        </span>
      </div>
      <p>{{ task.prSummary.summary }}</p>
      <p class="risk-profile-review">{{ task.prSummary.mergeRecommendation }}</p>
      <div class="risk-profile-signals">
        <span v-for="risk in task.prSummary.keyRisks" :key="risk">{{ risk }}</span>
      </div>
      <div v-if="task.prSummary.focusFiles.length" class="pr-summary-files">
        <code v-for="file in task.prSummary.focusFiles" :key="file">{{ file }}</code>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import type { PrRiskProfileViewModel, ReviewTaskDetail, ReviewStatus, RiskLevel } from "@/types";

defineProps<{
  task: ReviewTaskDetail;
  findingCount: number;
  findingCounts: Record<RiskLevel, number>;
  riskProfile?: PrRiskProfileViewModel;
  statusText: (status: ReviewStatus) => string;
  riskText: (risk: RiskLevel) => string;
  changeTypeText: (changeType: string) => string;
}>();
</script>
