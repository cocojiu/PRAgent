<template>
  <section class="dashboard-card">
    <h2>GitHub 评论反馈</h2>
    <p>在已发布的行评论下回复 /repoguard false-positive 原因 或 /repoguard ignore 原因。仅接受白名单仓库内有写权限的用户，误报只创建抑制提案。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="diagnostics">{{ diagnostics.enabled ? "反馈处理已启用" : "反馈处理未启用，请先完成 Webhook 事件订阅、签名和仓库白名单配置" }}</p>
    <el-button :loading="loading" @click="load">刷新反馈状态</el-button>
    <el-table :data="diagnostics?.events ?? []" row-key="id" aria-label="GitHub 反馈事件">
      <el-table-column prop="id" label="事件" width="85" />
      <el-table-column prop="taskId" label="任务" width="85" />
      <el-table-column prop="feedbackStatus" label="反馈" />
      <el-table-column prop="status" label="处理状态" />
      <el-table-column prop="failureCode" label="诊断" />
      <el-table-column prop="attempts" label="重试次数" width="90" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="diagnostics?.enabled && row.status === 'FAILED'" :loading="retrying === row.id" @click="retry(row.id)">重试</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无反馈事件" /></template>
    </el-table>
    <small>仅展示最近 20 条事件；已应用和已忽略事件不会被重新执行。</small>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchGithubFeedback, retryGithubFeedback } from "@/api/config";
import type { GithubFeedbackDiagnostics } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const diagnostics = ref<GithubFeedbackDiagnostics | null>(null);
const error = ref("");
const loading = ref(false);
const retrying = ref<number | null>(null);
const load = async () => {
  loading.value = true;
  error.value = "";
  try { diagnostics.value = await fetchGithubFeedback(); }
  catch (reason) { error.value = getErrorMessage(reason, "反馈状态加载失败"); }
  finally { loading.value = false; }
};
const retry = async (id: number) => {
  retrying.value = id;
  try { await retryGithubFeedback(id); await load(); }
  catch (reason) { error.value = getErrorMessage(reason, "反馈重试失败"); }
  finally { retrying.value = null; }
};
onMounted(load);
</script>
