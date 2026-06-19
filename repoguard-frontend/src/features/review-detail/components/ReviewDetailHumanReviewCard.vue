<template>
  <article v-if="task.humanReviewRequired" class="dashboard-card human-review-card">
    <div class="card-title-row">
      <h2>人工审查门禁</h2>
      <span :class="`status-pill ${humanReviewStatusClass}`">{{ humanReviewStatusText }}</span>
    </div>
    <p class="human-review-note">
      {{ task.humanReviewNote || "中高风险审查结果需要人工确认后才能回写 GitHub 评论。" }}
    </p>
    <dl v-if="task.humanReviewBy || task.humanReviewedAt" class="human-review-meta">
      <dt>审查人</dt><dd>{{ task.humanReviewBy || "-" }}</dd>
      <dt>审查时间</dt><dd>{{ task.humanReviewedAt || "-" }}</dd>
    </dl>
    <div class="human-review-actions">
      <el-button
        type="success"
        :disabled="!canManage || !canSubmitHumanReview"
        :loading="submittingHumanReview"
        @click="$emit('submit', 'approve')"
      >
        通过审查
      </el-button>
      <el-button
        type="warning"
        :disabled="!canManage || !canSubmitHumanReview"
        :loading="submittingHumanReview"
        @click="$emit('submit', 'changes_requested')"
      >
        要求修改
      </el-button>
      <el-button
        type="danger"
        plain
        :disabled="!canManage || !canSubmitHumanReview"
        :loading="submittingHumanReview"
        @click="$emit('submit', 'reject')"
      >
        拒绝
      </el-button>
    </div>
  </article>
</template>

<script setup lang="ts">
import type { HumanReviewRequest, ReviewTaskDetail } from "@/types";

defineProps<{
  canManage: boolean;
  canSubmitHumanReview: boolean;
  humanReviewStatusClass: string;
  humanReviewStatusText: string;
  submittingHumanReview: boolean;
  task: ReviewTaskDetail;
}>();

defineEmits<{
  submit: [action: HumanReviewRequest["action"]];
}>();
</script>
