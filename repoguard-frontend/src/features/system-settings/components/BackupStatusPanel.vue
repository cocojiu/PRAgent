<template>
  <article class="dashboard-card" aria-label="定时数据库备份状态">
    <h2>定时数据库备份</h2>
    <p>查看最近一次执行记录。此处不执行备份、恢复或清理操作。</p>
    <el-button :loading="loading" @click="load">刷新备份记录</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="status">
      <p role="status">{{ statusLabel }}</p>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="查询时间">{{ formatDateTime(status.checkedAt) }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ status.startedAt ? formatDateTime(status.startedAt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ status.finishedAt ? formatDateTime(status.finishedAt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="成功记录有效期">{{ status.maxAgeHours }} 小时</el-descriptions-item>
        <el-descriptions-item label="记录中的保留份数">{{ status.retained ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="记录中的归档大小">{{ status.archiveBytes == null ? '—' : `${(status.archiveBytes / 1048576).toFixed(2)} MiB` }}</el-descriptions-item>
        <el-descriptions-item v-if="status.reasonCode" label="失败原因">{{ failureReason }}</el-descriptions-item>
      </el-descriptions>
      <el-alert title="执行记录成功不等于备份可恢复。本页未验证归档当前完整性、恢复能力、定时器是否启用或进程是否仍在运行。" type="info" :closable="false" />
      <p v-if="status.status === 'DISABLED'">尚未配置状态数据源，请由运维人员完成只读状态目录接入。</p>
      <p v-else-if="status.status !== 'SUCCESS_RECORDED'">{{ status.status === 'RUNNING_RECORDED' ? '记录显示执行中，可稍后刷新确认最终结果。' : '请检查服务器上的备份服务和执行日志，确认最近一次成功备份。' }}</p>
    </template>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fetchBackupStatus } from "@/api/config";
import type { BackupStatus } from "@/types";
import { formatDateTime } from "@/utils/dateTime";
import { getErrorMessage } from "@/utils/errors";

const status = ref<BackupStatus | null>(null);
const loading = ref(false);
const error = ref("");
let request: AbortController | undefined;
let disposed = false;
const labels: Record<string, string> = {
  DISABLED: "未配置状态数据源", MISSING: "尚未发现备份记录",
  UNREADABLE_OR_INVALID: "备份记录不可读取或格式无效", SUCCESS_RECORDED: "最近执行记录成功",
  RUNNING_RECORDED: "记录显示执行中", RUN_OVERRUN: "执行记录超时未结束",
  FAILED: "最近执行失败", STALE: "最近成功记录已过期", STORAGE_LIMIT: "备份存储预算已超限"
};
const statusLabel = computed(() => labels[status.value?.status ?? ""] ?? "未知备份状态，请核查服务日志");
const failureReason = computed(() => ({
  backup_storage_budget_exhausted: "备份存储预算或剩余空间不足",
  backup_timeout: "备份执行超时", service_interrupted: "备份服务中断",
  archive_checksum_mismatch: "归档校验不一致", completed_backup_not_verified: "新归档尚未通过验证",
  backup_command_failed: "备份命令执行失败"
} as Record<string, string>)[status.value?.reasonCode ?? ""] ?? "执行未完成，请核查服务器日志");
const load = async () => {
  if (disposed || loading.value) return;
  const current = new AbortController();
  request = current;
  loading.value = true;
  error.value = "";
  status.value = null;
  try {
    const result = await fetchBackupStatus({ signal: current.signal });
    if (!disposed && request === current) status.value = result;
  } catch (reason) {
    if (!disposed && request === current) error.value = getErrorMessage(reason, "备份记录查询失败");
  } finally {
    if (!disposed && request === current) loading.value = false;
  }
};
onMounted(load);
onBeforeUnmount(() => { disposed = true; request?.abort(); });
</script>
