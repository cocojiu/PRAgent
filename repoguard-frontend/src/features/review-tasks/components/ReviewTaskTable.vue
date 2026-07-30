<template>
  <el-table :data="tasks" class="rg-table task-table" size="large" aria-label="审查任务列表">
    <el-table-column label="PR" min-width="230">
      <template #default="{ row }">
        <div class="pr-cell">
          <Github :size="20" />
          <div>
            <span v-if="isRetrying(row)" class="pr-link disabled-link">
              #{{ row.prNumber }}
            </span>
            <RouterLink v-else class="pr-link" :to="{ name: 'task-detail', params: { id: row.id } }">
              #{{ row.prNumber }}
            </RouterLink>
            <p>{{ row.title }}</p>
          </div>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="仓库" min-width="150">
      <template #default="{ row }">
        <div class="repo-cell">
          <strong>{{ row.repository }}</strong>
          <span>{{ row.organization }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="Commit" width="140">
      <template #default="{ row }">
        <code>{{ row.commit }}</code>
        <Copy :size="15" class="copy-icon" />
      </template>
    </el-table-column>
    <el-table-column label="来源" width="130">
      <template #default="{ row }">
        <span :class="`source-pill ${reviewTaskSourceClass(row.triggerSource || row.source)}`">
          {{ reviewTaskSourceText(row.triggerSource || row.source) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="120">
      <template #default="{ row }">
        <span :class="`status-pill ${statusClass(row.status)}`">{{ statusText(row.status) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="评估完整性" width="130">
      <template #default="{ row }">
        <el-tooltip :content="assessmentStatusDescription(row.assessmentStatus)" placement="top">
          <span :class="`status-pill ${assessmentStatusClass(row.assessmentStatus)}`">
            {{ assessmentStatusText(row.assessmentStatus) }}
          </span>
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column label="失败原因" min-width="210">
      <template #default="{ row }">
        <el-tooltip v-if="row.failureReason && row.failureSuggestion" :content="row.failureSuggestion" placement="top">
          <span class="failure-summary-cell">
            <ShieldAlert :size="15" />
            <span>{{ row.failureReason }}</span>
          </span>
        </el-tooltip>
        <span v-else-if="row.failureReason" class="failure-summary-cell">
          <ShieldAlert :size="15" />
          <span>{{ row.failureReason }}</span>
        </span>
        <span v-else class="muted-text">-</span>
      </template>
    </el-table-column>
    <el-table-column label="风险等级" width="130">
      <template #default="{ row }">
        <span :class="`risk-pill ${row.riskLevel}`">{{ riskText(row.riskLevel) }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="mqRetries" label="MQ 重试" width="110" />
    <el-table-column label="LLM 状态" width="130">
      <template #default="{ row }">
        <span :class="`status-pill ${statusClass(row.llmStatus)}`">{{ statusText(row.llmStatus) }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="createdAt" label="创建时间" min-width="180" />
    <el-table-column label="操作" width="230" fixed="right">
      <template #default="{ row }">
        <div class="table-actions">
          <el-button type="primary" size="small" :disabled="isRetrying(row)" @click="emit('view', row.id)">查看</el-button>
          <el-tooltip :content="reviewTaskRetryTooltip(row)">
            <span>
              <el-button
                size="small"
                :disabled="!canManage || !canRetryReviewTask(row)"
                :loading="retryingTaskId === row.id"
                @click="emit('retry', row)"
              >
                {{ reviewTaskRetryText(row) }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty description="暂无符合条件的审查任务" />
    </template>
  </el-table>

  <div class="table-footer">
    <span>共 {{ total }} 条</span>
    <el-pagination
      v-model:current-page="currentPageModel"
      v-model:page-size="pageSizeModel"
      layout="sizes, prev, pager, next, jumper"
      :page-sizes="[5, 8, 10, 20]"
      :total="total"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { Copy, ShieldAlert } from "@lucide/vue";
import Github from "@/components/icons/GithubIcon.vue";
import type { ReviewTask } from "@/types";
import {
  assessmentStatusClass,
  assessmentStatusDescription,
  assessmentStatusText
} from "@/utils/assessment";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";
import {
  canRetryReviewTask,
  reviewTaskRetryText,
  reviewTaskRetryTooltip,
  reviewTaskSourceClass,
  reviewTaskSourceText
} from "../reviewTaskDisplayMappers";

const props = defineProps<{
  canManage: boolean;
  currentPage: number;
  pageSize: number;
  retryingTaskId?: number;
  tasks: ReviewTask[];
  total: number;
}>();

const emit = defineEmits<{
  retry: [task: ReviewTask];
  "update:currentPage": [value: number];
  "update:pageSize": [value: number];
  view: [id: number];
}>();

const currentPageModel = computed({
  get: () => props.currentPage,
  set: (value: number) => emit("update:currentPage", value)
});

const pageSizeModel = computed({
  get: () => props.pageSize,
  set: (value: number) => emit("update:pageSize", value)
});

const isRetrying = (task: ReviewTask) => props.retryingTaskId === task.id;
</script>
