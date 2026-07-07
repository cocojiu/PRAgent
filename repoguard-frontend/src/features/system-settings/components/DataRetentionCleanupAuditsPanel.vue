<template>
  <article class="dashboard-card data-retention-audit-card">
    <div class="data-retention-audit-head">
      <div>
        <h2>数据保留清理审计</h2>
      </div>
      <el-button type="primary" plain :loading="loading" @click="loadAudits">
        <RefreshCw :size="16" />
        刷新
      </el-button>
    </div>

    <div class="filter-bar data-retention-audit-filter">
      <el-select v-model="filter.mode" placeholder="全部模式" clearable>
        <el-option label="预检" value="dry_run" />
        <el-option label="执行" value="execute" />
      </el-select>
      <el-select v-model="filter.status" placeholder="全部状态" clearable>
        <el-option label="执行中" value="STARTED" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-input
        v-model="filter.backupReference"
        clearable
        placeholder="备份凭证"
        @keyup.enter="reloadFromFirstPage"
      />
      <el-button :loading="loading" @click="reloadFromFirstPage">筛选</el-button>
    </div>

    <el-table
      :data="audits"
      class="rg-table data-retention-audit-table"
      size="large"
      aria-label="数据保留清理审计记录"
    >
      <el-table-column prop="id" label="批次" width="92" />
      <el-table-column label="模式" width="96">
        <template #default="{ row }">{{ cleanupAuditModeText(row.mode) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="108">
        <template #default="{ row }">
          <span :class="`status-pill ${cleanupAuditStatusClass(row.status)}`">
            {{ cleanupAuditStatusText(row.status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="开始时间" min-width="162" />
      <el-table-column prop="completedAt" label="完成时间" min-width="162">
        <template #default="{ row }">{{ row.completedAt || "-" }}</template>
      </el-table-column>
      <el-table-column label="保留/上限" width="120">
        <template #default="{ row }">{{ row.retentionDays ?? "-" }} / {{ row.maxTasks ?? "-" }}</template>
      </el-table-column>
      <el-table-column label="任务" min-width="210">
        <template #default="{ row }">{{ cleanupAuditTaskSummaryText(row) }}</template>
      </el-table-column>
      <el-table-column label="子表删除" min-width="250">
        <template #default="{ row }">{{ cleanupAuditDeletedChildrenText(row) }}</template>
      </el-table-column>
      <el-table-column label="备份凭证" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ cleanupAuditBackupReferenceText(row) }}</template>
      </el-table-column>
      <el-table-column label="失败详情" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ cleanupAuditFailureText(row) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无清理审计记录" />
      </template>
    </el-table>

    <div class="table-footer">
      <span>共 {{ total }} 条</span>
      <el-pagination
        v-model:current-page="filter.page"
        v-model:page-size="filter.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="prev, pager, next, sizes"
        @current-change="loadAudits"
        @size-change="reloadFromFirstPage"
      />
    </div>
  </article>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { RefreshCw } from "lucide-vue-next";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchDataRetentionCleanupAudits } from "@/api/config";
import {
  cleanupAuditBackupReferenceText,
  cleanupAuditDeletedChildrenText,
  cleanupAuditFailureText,
  cleanupAuditModeText,
  cleanupAuditStatusClass,
  cleanupAuditStatusText,
  cleanupAuditTaskSummaryText
} from "@/features/system-settings/dataRetentionCleanupAuditDisplayMappers";
import { getErrorMessage } from "@/utils/errors";
import type { DataRetentionCleanupAudit } from "@/types";

const loading = ref(false);
const audits = ref<DataRetentionCleanupAudit[]>([]);
const total = ref(0);
const filter = reactive({
  page: 1,
  pageSize: 10,
  mode: "",
  status: "",
  backupReference: ""
});

const loadAudits = async () => {
  loading.value = true;
  try {
    const result = await fetchDataRetentionCleanupAudits({
      page: filter.page,
      pageSize: filter.pageSize,
      mode: filter.mode || undefined,
      status: filter.status || undefined,
      backupReference: filter.backupReference || undefined
    });
    audits.value = result.items;
    total.value = result.total;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "数据保留清理审计加载失败"));
  } finally {
    loading.value = false;
  }
};

const reloadFromFirstPage = () => {
  filter.page = 1;
  void loadAudits();
};

onMounted(() => {
  void loadAudits();
});
</script>
