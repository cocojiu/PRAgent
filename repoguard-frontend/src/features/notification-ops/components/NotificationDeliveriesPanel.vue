<template>
  <article class="task-panel">
    <div class="filter-bar notification-filter-bar">
      <el-select v-model="filter.status" placeholder="全部状态" clearable>
        <el-option label="SUCCESS" value="SUCCESS" />
        <el-option label="FAILED" value="FAILED" />
        <el-option label="SKIPPED" value="SKIPPED" />
      </el-select>
      <el-input-number v-model="filter.taskId" :min="1" :controls="false" placeholder="Task ID" />
      <el-button type="primary" plain :loading="loading" @click="$emit('refresh')">
        <RefreshCw :size="16" />
        刷新
      </el-button>
    </div>
    <el-table :data="deliveries" class="rg-table task-table" size="large">
      <el-table-column prop="id" label="ID" width="86" />
      <el-table-column prop="eventId" label="事件" width="96" />
      <el-table-column prop="bindingId" label="绑定" width="96" />
      <el-table-column prop="taskId" label="任务" width="96" />
      <el-table-column label="平台" width="120">
        <template #default="{ row }">{{ providerText(row.provider) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <span :class="`status-pill ${notificationStatusClass(row.status)}`">{{ notificationStatusText(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="attemptCount" label="次数" width="86" />
      <el-table-column prop="failureReason" label="失败原因" min-width="240" show-overflow-tooltip />
      <el-table-column prop="sentAt" label="发送时间" min-width="160" />
    </el-table>
  </article>
</template>

<script setup lang="ts">
import { RefreshCw } from "lucide-vue-next";
import {
  notificationStatusClass,
  notificationStatusText,
  providerText
} from "../notificationOpsDisplayMappers";
import type { NotificationDelivery } from "@/types";

type NotificationRecordFilter = {
  page: number;
  pageSize: number;
  status?: string;
  taskId?: number;
};

defineProps<{
  deliveries: NotificationDelivery[];
  filter: NotificationRecordFilter;
  loading: boolean;
}>();

defineEmits<{
  refresh: [];
}>();
</script>
