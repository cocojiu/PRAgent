<template>
  <article class="task-panel">
    <div class="filter-bar notification-filter-bar">
      <el-select v-model="filter.status" placeholder="全部状态" clearable>
        <el-option label="PENDING" value="PENDING" />
        <el-option label="PUBLISHING" value="PUBLISHING" />
        <el-option label="PUBLISHED" value="PUBLISHED" />
        <el-option label="DELIVERING" value="DELIVERING" />
        <el-option label="DELIVERED" value="DELIVERED" />
        <el-option label="PUBLISH_FAILED" value="PUBLISH_FAILED" />
        <el-option label="DELIVERY_FAILED" value="DELIVERY_FAILED" />
        <el-option label="DEAD" value="DEAD" />
      </el-select>
      <el-input-number v-model="filter.taskId" :min="1" :controls="false" placeholder="Task ID" />
      <el-button type="primary" plain :loading="loading" @click="$emit('refresh')">
        <RefreshCw :size="16" />
        刷新
      </el-button>
    </div>
    <el-table :data="events" class="rg-table task-table" size="large">
      <el-table-column prop="id" label="ID" width="86" />
      <el-table-column label="事件类型" min-width="190">
        <template #default="{ row }">{{ eventTypeText(row.eventType) }}</template>
      </el-table-column>
      <el-table-column prop="taskId" label="任务" width="100" />
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <span :class="`status-pill ${notificationStatusClass(row.status)}`">{{ notificationStatusText(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="渠道" min-width="150">
        <template #default="{ row }">
          <span class="channel-cell">
            <component :is="channelIcon(row)" :size="18" />
            {{ channelText(row) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="投递数" width="96">
        <template #default="{ row }">{{ row.deliverySummary?.deliveryCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="失败投递" width="110">
        <template #default="{ row }">
          <span :class="{ 'danger-count': (row.deliverySummary?.failedDeliveryCount ?? 0) > 0 }">
            {{ row.deliverySummary?.failedDeliveryCount ?? 0 }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="最近投递状态" width="140">
        <template #default="{ row }">
          <span :class="`status-pill ${notificationStatusClass(row.deliverySummary?.latestDeliveryStatus || '')}`">
            {{ notificationStatusText(row.deliverySummary?.latestDeliveryStatus || "") }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="retryCount" label="重试" width="86" />
      <el-table-column prop="nextRetryAt" label="下次重试" min-width="160" />
      <el-table-column prop="lastError" label="最近错误" min-width="240" show-overflow-tooltip />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="!canManage || !canRetryNotificationEvent(row.status)"
            :loading="retryingEventId === row.id"
            @click="$emit('retry', row.id)"
          >
            重试
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </article>
</template>

<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import {
  canRetryNotificationEvent,
  channelIcon,
  channelText,
  eventTypeText,
  notificationStatusClass,
  notificationStatusText
} from "../notificationOpsDisplayMappers";
import type { NotificationEvent } from "@/types";

type NotificationRecordFilter = {
  page: number;
  pageSize: number;
  status?: string;
  taskId?: number;
};

defineProps<{
  canManage: boolean;
  events: NotificationEvent[];
  filter: NotificationRecordFilter;
  loading: boolean;
  retryingEventId?: number;
}>();

defineEmits<{
  refresh: [];
  retry: [id: number];
}>();
</script>
