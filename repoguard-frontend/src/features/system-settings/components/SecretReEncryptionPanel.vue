<template>
  <article class="dashboard-card secret-re-encryption-card">
    <div class="secret-re-encryption-head">
      <div>
        <h2>密钥重加密任务</h2>
        <p>以后台分批任务检查或重加密密文字段，支持暂停、恢复和分页查看处理明细。</p>
      </div>
      <div class="secret-re-encryption-actions">
        <el-button
          :disabled="!canRun"
          :loading="runningMode === 'dryRun'"
          @click="runReEncryption(false)"
        >
          预检
        </el-button>
        <el-button
          type="primary"
          :disabled="!canRun"
          :loading="runningMode === 'execute'"
          @click="runReEncryption(true)"
        >
          执行重加密
        </el-button>
        <el-button
          v-if="canPause"
          :loading="runningMode === 'pause'"
          @click="pauseJob"
        >
          暂停
        </el-button>
        <el-button
          v-if="canResume"
          type="primary"
          plain
          :loading="runningMode === 'resume'"
          @click="resumeJob"
        >
          继续
        </el-button>
      </div>
    </div>

    <el-form label-position="top" class="secret-re-encryption-form">
      <el-form-item label="源加密密钥">
        <el-input v-model="form.sourceEncryptionKey" type="password" show-password :disabled="!canManage || isFormLocked" />
      </el-form-item>
      <el-form-item label="源 Key ID">
        <el-input v-model="form.sourceKeyId" :disabled="!canManage || isFormLocked" placeholder="local" />
      </el-form-item>
      <el-form-item label="目标加密密钥">
        <el-input v-model="form.targetEncryptionKey" type="password" show-password :disabled="!canManage || isFormLocked" />
      </el-form-item>
      <el-form-item label="目标 Key ID">
        <el-input v-model="form.targetKeyId" :disabled="!canManage || isFormLocked" />
      </el-form-item>
      <el-form-item label="执行确认">
        <el-input v-model="form.confirmText" :disabled="!canManage || isFormLocked" placeholder="RE-ENCRYPT" />
      </el-form-item>
    </el-form>

    <el-alert
      v-if="keyIdsConflict"
      title="目标 Key ID 必须与源 Key ID 不同，否则无法可靠识别已完成轮换的密文。"
      type="warning"
      :closable="false"
      show-icon
      class="secret-re-encryption-summary"
    />

    <el-alert
      v-if="summaryText"
      :title="summaryText"
      :type="summaryTone"
      :closable="false"
      show-icon
      class="secret-re-encryption-summary"
    />

    <el-table
      v-if="displayItems.length"
      :data="displayItems"
      class="rg-table secret-re-encryption-table"
      size="large"
      aria-label="密钥重加密字段预检结果"
    >
      <el-table-column prop="resourceText" label="资源" min-width="180" />
      <el-table-column prop="fieldText" label="字段" min-width="130" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="row.statusTone">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="keyText" label="Key ID" min-width="150" />
      <el-table-column prop="hint" label="处理建议" min-width="300" />
    </el-table>
    <el-empty v-else-if="canDisplayItems" description="没有可展示的密文字段" />
    <el-pagination
      v-if="itemsTotal > itemsPageSize"
      v-model:current-page="itemsPage"
      :page-size="itemsPageSize"
      :total="itemsTotal"
      layout="prev, pager, next, total"
      @current-change="loadItems"
    />
  </article>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import {
  fetchSecretReEncryptionJob,
  fetchSecretReEncryptionJobItems,
  fetchSecretReEncryptionJobs,
  pauseSecretReEncryptionJob,
  reEncryptSecrets,
  resumeSecretReEncryptionJob
} from "@/api/config";
import {
  secretReEncryptionSummaryText,
  toSecretReEncryptionDisplayItems
} from "@/features/system-settings/secretReEncryptionDisplayMappers";
import {
  buildSecretReEncryptionRequest,
  canSubmitSecretReEncryption,
  SECRET_RE_ENCRYPTION_CONFIRM_TEXT,
  secretReEncryptionExecutionConfirmMessage
} from "@/features/system-settings/secretReEncryptionRequestBuilders";
import { getErrorMessage } from "@/utils/errors";
import type {
  SecretReEncryptionItem,
  SecretReEncryptionJob,
  SecretReEncryptionRequest
} from "@/types";

const props = withDefaults(defineProps<{
  canManage?: boolean;
}>(), {
  canManage: false
});

const form = reactive<SecretReEncryptionRequest>({
  sourceEncryptionKey: "",
  sourceKeyId: "local",
  targetEncryptionKey: "",
  targetKeyId: "",
  confirmText: ""
});
const result = ref<SecretReEncryptionJob | null>(null);
const items = ref<SecretReEncryptionItem[]>([]);
const itemsPage = ref(1);
const itemsPageSize = 50;
const itemsTotal = ref(0);
const runningMode = ref<"dryRun" | "execute" | "pause" | "resume" | null>(null);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollingEnabled = false;
let disposed = false;
let pollingGeneration = 0;

const activeJob = computed(() => Boolean(
  result.value
  && ["PENDING", "RUNNING", "RETRY_WAIT"].includes(result.value.status)
));
const blockingJob = computed(() => Boolean(
  result.value
  && ["PENDING", "RUNNING", "RETRY_WAIT", "PAUSED"].includes(result.value.status)
));
const isRunning = computed(() => runningMode.value !== null);
const isFormLocked = computed(() => isRunning.value || blockingJob.value);
const keyIdsConflict = computed(() =>
  form.targetKeyId.trim().length > 0
  && form.targetKeyId.trim() === (form.sourceKeyId?.trim() || "local")
);
const canRun = computed(() =>
  props.canManage
  && !isFormLocked.value
  && canSubmitSecretReEncryption(form)
);
const canPause = computed(() => props.canManage && activeJob.value && !isRunning.value);
const canResume = computed(() =>
  props.canManage
  && !isRunning.value
  && ["PAUSED", "FAILED"].includes(result.value?.status ?? "")
);
const canDisplayItems = computed(() =>
  result.value
  && ["PAUSED", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED"].includes(result.value.status)
);
const displayItems = computed(() => toSecretReEncryptionDisplayItems(items.value));
const summaryText = computed(() => result.value ? secretReEncryptionSummaryText(result.value) : "");
const summaryTone = computed(() => {
  if (!result.value) {
    return "info";
  }
  if (
    result.value.failedCount > 0
    || ["PAUSED", "FAILED", "COMPLETED_WITH_ERRORS"].includes(result.value.status)
  ) {
    return "warning";
  }
  return activeJob.value ? "info" : "success";
});

const runReEncryption = async (execute: boolean) => {
  if (!canRun.value) {
    return;
  }
  if (execute && form.confirmText !== SECRET_RE_ENCRYPTION_CONFIRM_TEXT) {
    ElMessage.warning("执行重加密前需要填写确认文本 RE-ENCRYPT");
    return;
  }
  if (execute && !await confirmExecution()) {
    return;
  }
  runningMode.value = execute ? "execute" : "dryRun";
  try {
    result.value = await reEncryptSecrets(buildSecretReEncryptionRequest(form, execute));
    items.value = [];
    itemsTotal.value = 0;
    itemsPage.value = 1;
    clearSensitiveInputs();
    ElMessage.success(execute ? "密钥重加密任务已创建" : "密钥重加密预检任务已创建");
    startPolling();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密任务创建失败"));
  } finally {
    runningMode.value = null;
  }
};

const refreshJob = async (expectedGeneration?: number) => {
  if (!result.value) {
    return;
  }
  const refreshed = await fetchSecretReEncryptionJob(result.value.id);
  if (
    expectedGeneration !== undefined
    && (!pollingEnabled || expectedGeneration !== pollingGeneration)
  ) {
    return;
  }
  result.value = refreshed;
  if (!activeJob.value) {
    await loadItems(itemsPage.value);
  }
};

const loadLatestJob = async () => {
  if (!props.canManage) {
    return;
  }
  try {
    const response = await fetchSecretReEncryptionJobs(1, 1);
    result.value = response.items[0] ?? null;
    if (!result.value) {
      return;
    }
    itemsPage.value = 1;
    if (activeJob.value) {
      startPolling();
    } else {
      await loadItems(1);
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密任务加载失败"));
  }
};

const startPolling = () => {
  pollingEnabled = true;
  pollingGeneration += 1;
  schedulePoll(pollingGeneration);
};

const schedulePoll = (expectedGeneration: number) => {
  clearPollTimer();
  if (
    disposed
    || !pollingEnabled
    || expectedGeneration !== pollingGeneration
    || !activeJob.value
  ) {
    pollingEnabled = false;
    return;
  }
  pollTimer = setTimeout(async () => {
    pollTimer = undefined;
    try {
      await refreshJob(expectedGeneration);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "密钥重加密任务状态刷新失败"));
    } finally {
      if (!disposed && pollingEnabled && expectedGeneration === pollingGeneration) {
        schedulePoll(expectedGeneration);
      }
    }
  }, 1500);
};

const clearPollTimer = () => {
  if (pollTimer !== undefined) {
    clearTimeout(pollTimer);
    pollTimer = undefined;
  }
};

const stopPolling = () => {
  pollingEnabled = false;
  pollingGeneration += 1;
  clearPollTimer();
};

const loadItems = async (page = itemsPage.value) => {
  if (!result.value) {
    return;
  }
  itemsPage.value = page;
  try {
    const response = await fetchSecretReEncryptionJobItems(
      result.value.id,
      itemsPage.value,
      itemsPageSize
    );
    items.value = response.items;
    itemsTotal.value = response.total;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密明细加载失败"));
  }
};

const pauseJob = async () => {
  if (!result.value || !canPause.value) {
    return;
  }
  runningMode.value = "pause";
  stopPolling();
  try {
    result.value = await pauseSecretReEncryptionJob(result.value.id);
    await loadItems();
    ElMessage.success("密钥重加密任务已暂停");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密任务暂停失败"));
    startPolling();
  } finally {
    runningMode.value = null;
  }
};

const resumeJob = async () => {
  if (!result.value || !canResume.value) {
    return;
  }
  runningMode.value = "resume";
  try {
    result.value = await resumeSecretReEncryptionJob(result.value.id);
    ElMessage.success("密钥重加密任务已恢复");
    startPolling();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密任务恢复失败"));
  } finally {
    runningMode.value = null;
  }
};

const confirmExecution = async () => {
  try {
    await ElMessageBox.confirm(
      secretReEncryptionExecutionConfirmMessage(form),
      "确认执行密钥重加密",
      {
        type: "warning",
        confirmButtonText: "执行重加密",
        cancelButtonText: "取消"
      }
    );
    return true;
  } catch {
    return false;
  }
};

const clearSensitiveInputs = () => {
  form.sourceEncryptionKey = "";
  form.targetEncryptionKey = "";
  form.confirmText = "";
};

onMounted(loadLatestJob);
onUnmounted(() => {
  disposed = true;
  stopPolling();
});
</script>
