<template>
  <div class="settings-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>系统设置</h1>
        <p>配置系统基础信息、审查策略、通知与安全选项</p>
      </div>
      <el-button type="primary" size="large" @click="saveSettings">保存设置</el-button>
    </div>

    <section class="settings-grid">
      <article class="dashboard-card settings-card">
        <h2>基础设置</h2>
        <el-form label-position="top">
          <el-form-item label="系统名称"><el-input v-model="baseForm.systemName" /></el-form-item>
          <el-form-item label="默认语言">
            <el-select v-model="baseForm.language">
              <el-option label="中文" value="中文" />
              <el-option label="English" value="English" />
            </el-select>
          </el-form-item>
          <el-form-item label="时区"><el-input v-model="baseForm.timezone" /></el-form-item>
          <el-form-item label="数据保留（天）"><el-input-number v-model="baseForm.retentionDays" :min="1" :max="365" /></el-form-item>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>审查策略</h2>
        <el-form label-position="top">
          <el-form-item label="最大 Diff 行数"><el-input-number v-model="policyForm.maxDiffLines" :min="100" :max="5000" /></el-form-item>
          <el-form-item label="LLM 超时时间（秒）"><el-input-number v-model="policyForm.llmTimeoutSeconds" :min="10" :max="300" /></el-form-item>
          <el-form-item label="Worker 并发数"><el-input-number v-model="policyForm.workerConcurrency" :min="1" :max="10" /></el-form-item>
          <div class="switch-row"><span>自动评论</span><el-switch v-model="policyForm.autoComment" /></div>
          <div class="switch-row"><span>失败自动重试</span><el-switch v-model="policyForm.autoRetry" /></div>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>通知设置</h2>
        <div class="switch-row"><span>GitHub 评论通知</span><el-switch v-model="notificationForm.githubComment" /></div>
        <div class="switch-row"><span>高风险 PR 通知</span><el-switch v-model="notificationForm.highRiskPr" /></div>
        <div class="switch-row"><span>失败任务通知</span><el-switch v-model="notificationForm.failedTask" /></div>
        <el-form label-position="top" class="settings-form-gap">
          <el-form-item label="通知邮箱"><el-input v-model="notificationForm.email" /></el-form-item>
        </el-form>
      </article>

      <article class="dashboard-card settings-card">
        <h2>安全设置</h2>
        <div class="switch-row"><span>Webhook 签名校验</span><el-switch v-model="securityForm.webhookSignature" /></div>
        <div class="switch-row"><span>密钥脱敏</span><el-switch v-model="securityForm.secretMasking" /></div>
        <div class="switch-row"><span>允许公开仓库</span><el-switch v-model="securityForm.publicRepoAllowed" /></div>
        <el-form label-position="top" class="settings-form-gap">
          <el-form-item label="Token 有效期（天）"><el-input-number v-model="securityForm.tokenTtlDays" :min="1" :max="180" /></el-form-item>
        </el-form>
      </article>
    </section>

    <section class="settings-bottom-grid">
      <article class="dashboard-card preview-card">
        <h2>配置预览</h2>
        <pre>system:
  name: {{ baseForm.systemName }}
  language: {{ baseForm.language }}
review:
  maxDiffLines: {{ policyForm.maxDiffLines }}
  llmTimeoutSeconds: {{ policyForm.llmTimeoutSeconds }}
security:
  webhookSignature: {{ securityForm.webhookSignature }}</pre>
      </article>
      <article class="dashboard-card">
        <h2>操作日志</h2>
        <el-table :data="settingLogs" class="rg-table" size="large">
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
import { reactive } from "vue";
import { ElMessage } from "element-plus";
import {
  baseSettings,
  notificationSettings,
  reviewPolicySettings,
  securitySettings,
  settingLogs
} from "@/mocks/settings";

const baseForm = reactive({ ...baseSettings });
const policyForm = reactive({ ...reviewPolicySettings });
const notificationForm = reactive({ ...notificationSettings });
const securityForm = reactive({ ...securitySettings });

const saveSettings = () => ElMessage.success("系统设置已保存");
</script>
