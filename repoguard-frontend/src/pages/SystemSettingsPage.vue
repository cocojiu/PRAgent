<template>
  <div v-loading="loading" class="settings-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>系统设置</h1>
        <p>配置系统基础信息、审查策略、通知与安全选项</p>
      </div>
      <div class="settings-actions">
        <el-button v-if="isEditing" size="large" @click="cancelEdit">取消</el-button>
        <el-button v-if="!isEditing" type="primary" size="large" @click="startEdit">编辑设置</el-button>
        <el-button v-else type="primary" size="large" :loading="saving" @click="saveSettings">保存设置</el-button>
      </div>
    </div>

    <el-alert
      v-if="!isEditing"
      title="当前为查看模式，点击右上角编辑设置后可修改配置。"
      type="info"
      :closable="false"
      show-icon
      class="settings-alert"
    />

    <section class="settings-grid">
      <article class="dashboard-card settings-card">
        <h2>基础设置</h2>
        <el-form label-position="top">
          <el-form-item label="系统名称">
            <el-input v-model="baseForm.systemName" :disabled="!isEditing" />
          </el-form-item>
          <el-form-item label="默认语言">
            <el-select v-model="baseForm.language" :disabled="!isEditing">
              <el-option label="中文" value="中文" />
              <el-option label="English" value="English" />
            </el-select>
          </el-form-item>
          <el-form-item label="时区">
            <el-input v-model="baseForm.timezone" :disabled="!isEditing" />
          </el-form-item>
          <el-form-item label="数据保留天数">
            <el-input-number v-model="baseForm.retentionDays" :disabled="!isEditing" :min="1" :max="365" />
          </el-form-item>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>审查策略</h2>
        <el-form label-position="top">
          <el-form-item label="最大 Diff 行数">
            <el-input-number v-model="policyForm.maxDiffLines" :disabled="!isEditing" :min="100" :max="5000" />
          </el-form-item>
          <el-form-item label="LLM 超时时间（秒）">
            <el-input-number v-model="policyForm.llmTimeoutSeconds" :disabled="!isEditing" :min="10" :max="300" />
          </el-form-item>
          <el-form-item label="Worker 并发数">
            <el-input-number v-model="policyForm.workerConcurrency" :disabled="!isEditing" :min="1" :max="10" />
          </el-form-item>
          <div class="switch-row">
            <span>自动评论</span>
            <el-switch v-model="policyForm.autoComment" :disabled="!isEditing" />
          </div>
          <div class="switch-row">
            <span>失败自动重试</span>
            <el-switch v-model="policyForm.autoRetry" :disabled="!isEditing" />
          </div>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>通知设置</h2>
        <div class="switch-row">
          <span>GitHub 评论通知</span>
          <el-switch v-model="notificationForm.githubComment" :disabled="!isEditing" />
        </div>
        <div class="switch-row">
          <span>高风险 PR 通知</span>
          <el-switch v-model="notificationForm.highRiskPr" :disabled="!isEditing" />
        </div>
        <div class="switch-row">
          <span>失败任务通知</span>
          <el-switch v-model="notificationForm.failedTask" :disabled="!isEditing" />
        </div>
        <el-form label-position="top" class="settings-form-gap">
          <el-form-item label="通知邮箱">
            <el-input v-model="notificationForm.email" :disabled="!isEditing" />
          </el-form-item>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>安全设置</h2>
        <div class="switch-row">
          <span>Webhook 签名校验</span>
          <el-switch v-model="securityForm.webhookSignature" :disabled="!isEditing" />
        </div>
        <div class="switch-row">
          <span>密钥脱敏</span>
          <el-switch v-model="securityForm.secretMasking" :disabled="!isEditing" />
        </div>
        <div class="switch-row">
          <span>允许公开仓库</span>
          <el-switch v-model="securityForm.publicRepoAllowed" :disabled="!isEditing" />
        </div>
        <el-form label-position="top" class="settings-form-gap">
          <el-form-item label="Token 有效期（天）">
            <el-input-number v-model="securityForm.tokenTtlDays" :disabled="!isEditing" :min="1" :max="180" />
          </el-form-item>
        </el-form>
      </article>
    </section>

    <section class="settings-bottom-grid">
      <article class="dashboard-card preview-card">
        <h2>配置预览</h2>
        <pre>system:
  name: {{ baseForm.systemName }}
  language: {{ baseForm.language }}
  timezone: {{ baseForm.timezone }}
review:
  maxDiffLines: {{ policyForm.maxDiffLines }}
  llmTimeoutSeconds: {{ policyForm.llmTimeoutSeconds }}
  workerConcurrency: {{ policyForm.workerConcurrency }}
security:
  webhookSignature: {{ securityForm.webhookSignature }}
  secretMasking: {{ securityForm.secretMasking }}</pre>
      </article>
      <article class="dashboard-card">
        <h2>操作日志</h2>
        <el-table :data="settingLogs" class="rg-table" size="large" aria-label="系统设置操作日志">
          <el-table-column prop="time" label="时间" width="180" />
          <el-table-column prop="operator" label="操作人" width="100" />
          <el-table-column prop="action" label="操作内容" min-width="260" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="status-pill success">{{ row.status }}</span>
            </template>
          </el-table-column>
        </el-table>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { fetchReviewPolicyConfig, updateReviewPolicyConfig } from "@/api/config";
import { useFormSnapshot } from "@/composables/useFormSnapshot";
import {
  baseSettings,
  notificationSettings,
  reviewPolicySettings,
  securitySettings,
  settingLogs
} from "@/mocks/settings";
import type { ReviewPolicyConfig } from "@/types";

const isEditing = ref(false);
const loading = ref(false);
const saving = ref(false);
const reviewPolicyConfig = ref<ReviewPolicyConfig>();
const baseForm = reactive({ ...baseSettings });
const policyForm = reactive({ ...reviewPolicySettings });
const notificationForm = reactive({ ...notificationSettings });
const securityForm = reactive({ ...securitySettings });
const { captureSnapshot, restoreSnapshot } = useFormSnapshot({
  baseForm,
  policyForm,
  notificationForm,
  securityForm
});

const startEdit = () => {
  captureSnapshot();
  isEditing.value = true;
};

const cancelEdit = () => {
  restoreSnapshot();
  isEditing.value = false;
  ElMessage.info("已取消修改");
};

const saveSettings = () => {
  void persistSettings();
};

const loadReviewPolicy = async () => {
  loading.value = true;
  try {
    const config = await fetchReviewPolicyConfig();
    reviewPolicyConfig.value = config;
    policyForm.llmTimeoutSeconds = config.timeoutSeconds;
    policyForm.workerConcurrency = config.workerConcurrency;
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "Review policy load failed, using local defaults");
  } finally {
    loading.value = false;
  }
};

const persistSettings = async () => {
  saving.value = true;
  try {
    const current = reviewPolicyConfig.value;
    const saved = await updateReviewPolicyConfig({
      llmEnabled: current?.llmEnabled ?? true,
      llmProvider: current?.llmProvider ?? "dashscope",
      modelName: current?.modelName ?? "qwen-plus",
      baseUrl: current?.baseUrl,
      apiKey: current?.apiKey,
      timeoutSeconds: policyForm.llmTimeoutSeconds,
      temperature: current?.temperature ?? 0.2,
      maxTokens: current?.maxTokens ?? 4096,
      fallbackToRules: current?.fallbackToRules ?? true,
      workerConcurrency: policyForm.workerConcurrency
    });
    reviewPolicyConfig.value = saved;
    captureSnapshot();
    isEditing.value = false;
    ElMessage.success("系统设置已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Review policy save failed");
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  void loadReviewPolicy();
});
</script>
