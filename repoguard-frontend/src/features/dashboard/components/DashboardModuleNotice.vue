<template>
  <p v-if="state && (state.error || (state.loading && state.lastSuccessAt))" class="dashboard-module-notice" role="status">
    {{ state.error || "更新中" }}
    <span v-if="state.lastSuccessAt"> · 当前显示上次成功数据：{{ state.lastSuccessLabel }} {{ state.lastSuccessAt }}</span>
    <span v-else> · 暂无可用数据</span>
  </p>
</template>

<script setup lang="ts">
import type { DashboardModuleState } from "../composables/useDashboardOverview";
defineProps<{ state?: DashboardModuleState }>();
</script>

<style scoped>
.dashboard-module-notice { color: var(--el-color-warning-dark-2); font-size: 12px; margin: 8px 0; }
</style>
