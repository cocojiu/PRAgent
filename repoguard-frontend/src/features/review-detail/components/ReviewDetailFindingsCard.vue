<template>
  <article class="dashboard-card findings-card">
    <div class="card-title-row">
      <h2>LLM Findings</h2>
      <span class="count-badge">{{ findings.length }} 条</span>
    </div>
    <div v-if="findings.length" class="finding-list">
      <section v-for="(finding, index) in findings" :key="`${finding.file}-${finding.line}-${index}`" class="finding-item">
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
              @click="$emit('feedback', finding.id, 'valid')"
            >
              有效
            </el-button>
            <el-button
              size="small"
              type="warning"
              plain
              :disabled="!canManage || !finding.id"
              :loading="feedbackSavingId === finding.id"
              @click="$emit('feedback', finding.id, 'false_positive')"
            >
              误报
            </el-button>
            <el-button
              size="small"
              plain
              :disabled="!canManage || !finding.id"
              :loading="feedbackSavingId === finding.id"
              @click="$emit('feedback', finding.id, 'fixed')"
            >
              已修复
            </el-button>
            <el-button
              size="small"
              type="info"
              plain
              :disabled="!canManage || !finding.id"
              :loading="feedbackSavingId === finding.id"
              @click="$emit('feedback', finding.id, 'ignored')"
            >
              忽略
            </el-button>
          </div>
        </div>
      </section>
    </div>
    <el-empty v-else description="暂无审查问题" />
  </article>
</template>

<script setup lang="ts">
import type { FindingFeedbackStatus, ReviewFinding, RiskLevel } from "@/types";

defineProps<{
  canManage: boolean;
  feedbackSavingId: number | null;
  findings: ReviewFinding[];
  riskText: (risk: RiskLevel) => string;
  findingFeedbackStatusClass: (status?: FindingFeedbackStatus | string) => string;
  findingFeedbackStatusText: (status?: FindingFeedbackStatus | string) => string;
}>();

defineEmits<{
  feedback: [findingId: number, status: FindingFeedbackStatus];
}>();
</script>
