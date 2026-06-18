<template>
  <section class="detail-kpi-grid">
    <div class="detail-kpi">
      <div class="metric-icon metric-icon--blue"><Archive :size="26" /></div>
      <div><p>仓库</p><strong>{{ task.repository }}</strong><span>{{ task.organization }}</span></div>
    </div>
    <div class="detail-kpi">
      <div class="metric-icon metric-icon--purple"><GitBranch :size="26" /></div>
      <div><p>Commit</p><strong>{{ task.commit }} <Copy :size="15" /></strong><span>{{ task.branch }}</span></div>
    </div>
    <div class="detail-kpi">
      <div class="metric-icon metric-icon--green"><Clock :size="26" /></div>
      <div><p>耗时</p><strong>{{ task.duration }}</strong><span>开始于 {{ startedAt }}</span></div>
    </div>
    <div class="detail-kpi">
      <div class="metric-icon metric-icon--orange"><MessagesSquare :size="26" /></div>
      <div><p>审查发现</p><strong>{{ findingCount }} 条</strong><span>{{ changedFileCount }} 个变更文件</span></div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Archive, Clock, Copy, GitBranch, MessagesSquare } from "lucide-vue-next";
import type { ReviewTaskDetail } from "@/types";

defineProps<{
  task: ReviewTaskDetail;
  startedAt: string;
  findingCount: number;
  changedFileCount: number;
}>();
</script>
