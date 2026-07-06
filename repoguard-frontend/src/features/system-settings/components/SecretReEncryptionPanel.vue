<template>
  <article class="dashboard-card secret-re-encryption-card">
    <div class="secret-re-encryption-head">
      <div>
        <h2>密钥重加密预检</h2>
        <p>检查集成、审查策略和通知渠道中的密文字段，按字段显示处理状态。</p>
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
      </div>
    </div>

    <el-form label-position="top" class="secret-re-encryption-form">
      <el-form-item label="源加密密钥">
        <el-input v-model="form.sourceEncryptionKey" type="password" show-password :disabled="!canManage || isRunning" />
      </el-form-item>
      <el-form-item label="源 Key ID">
        <el-input v-model="form.sourceKeyId" :disabled="!canManage || isRunning" placeholder="local" />
      </el-form-item>
      <el-form-item label="目标加密密钥">
        <el-input v-model="form.targetEncryptionKey" type="password" show-password :disabled="!canManage || isRunning" />
      </el-form-item>
      <el-form-item label="目标 Key ID">
        <el-input v-model="form.targetKeyId" :disabled="!canManage || isRunning" />
      </el-form-item>
      <el-form-item label="执行确认">
        <el-input v-model="form.confirmText" :disabled="!canManage || isRunning" placeholder="RE-ENCRYPT" />
      </el-form-item>
    </el-form>

    <el-alert
      v-if="summaryText"
      :title="summaryText"
      :type="result?.failedCount ? 'warning' : 'success'"
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
    <el-empty v-else-if="result" description="没有可展示的密文字段" />
  </article>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { reEncryptSecrets } from "@/api/config";
import {
  secretReEncryptionSummaryText,
  toSecretReEncryptionDisplayItems
} from "@/features/system-settings/secretReEncryptionDisplayMappers";
import { getErrorMessage } from "@/utils/errors";
import type { SecretReEncryptionRequest, SecretReEncryptionResponse } from "@/types";

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
const result = ref<SecretReEncryptionResponse | null>(null);
const runningMode = ref<"dryRun" | "execute" | null>(null);

const isRunning = computed(() => runningMode.value !== null);
const canRun = computed(() =>
  props.canManage
  && !isRunning.value
  && form.sourceEncryptionKey.trim().length > 0
  && form.targetEncryptionKey.trim().length > 0
  && form.targetKeyId.trim().length > 0
);
const displayItems = computed(() => result.value ? toSecretReEncryptionDisplayItems(result.value.items) : []);
const summaryText = computed(() => result.value ? secretReEncryptionSummaryText(result.value) : "");

const runReEncryption = async (execute: boolean) => {
  if (!canRun.value) {
    return;
  }
  if (execute && form.confirmText !== "RE-ENCRYPT") {
    ElMessage.warning("执行重加密前需要填写确认文本 RE-ENCRYPT");
    return;
  }
  runningMode.value = execute ? "execute" : "dryRun";
  try {
    result.value = await reEncryptSecrets({
      sourceEncryptionKey: form.sourceEncryptionKey,
      sourceKeyId: form.sourceKeyId?.trim() || undefined,
      targetEncryptionKey: form.targetEncryptionKey,
      targetKeyId: form.targetKeyId.trim(),
      execute,
      confirmText: execute ? form.confirmText : undefined
    });
    ElMessage.success(execute ? "密钥重加密已完成" : "密钥重加密预检已完成");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "密钥重加密操作失败"));
  } finally {
    runningMode.value = null;
  }
};
</script>
