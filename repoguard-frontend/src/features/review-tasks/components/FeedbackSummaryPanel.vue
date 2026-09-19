<template>
  <section class="task-panel">
    <h2>人工反馈效果</h2>
    <p>近 30 天最近最多 1000 条反馈中的当前有效记录，按同一 PR 版本和发现指纹去重。数量代表观察样本，修复由人工确认。</p>
    <el-button :loading="loading" @click="load">刷新反馈统计</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="summary">
      <p>已检查 {{ summary.examinedCount }} 条，去除无效或重复记录 {{ summary.excludedOrDuplicateCount }} 条，纳入 {{ summary.sampleSize }} 条。</p>
      <el-alert v-if="summary.truncated" title="反馈超过观察上限，仅展示最近样本，不代表全部反馈。" type="info" :closable="false" />
      <el-table :data="summary.sources" aria-label="反馈来源统计">
        <el-table-column prop="source" label="来源" />
        <el-table-column prop="reviewed" label="人工反馈" />
        <el-table-column prop="valid" label="确认有效" />
        <el-table-column prop="falsePositive" label="误报" />
        <el-table-column prop="fixed" label="人工确认修复" />
        <el-table-column prop="ignored" label="忽略" />
        <el-table-column label="证据状态">
          <template #default="{ row }">{{ row.evidenceStatus === 'INSUFFICIENT_DATA' ? '数据不足' : '仅展示数量' }}</template>
        </el-table-column>
      </el-table>
      <h3>最近 20 条纳入明细</h3>
      <el-table :data="summary.details" row-key="findingId" aria-label="反馈明细">
        <el-table-column prop="repository" label="仓库" />
        <el-table-column prop="prNumber" label="PR" width="80" />
        <el-table-column prop="source" label="来源" width="90" />
        <el-table-column label="反馈"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="actor" label="确认人" />
        <el-table-column prop="feedbackAt" label="确认时间" />
        <el-table-column label="追溯">
          <template #default="{ row }"><router-link :to="`/repoguard/tasks/${row.taskId}`">任务 {{ row.taskId }} · Finding {{ row.findingId }}</router-link></template>
        </el-table-column>
        <template #empty><el-empty description="暂无可追溯人工反馈，数据不足" /></template>
      </el-table>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchFeedbackSummary } from "@/api/config";
import type { FeedbackSummary } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const summary = ref<FeedbackSummary | null>(null);
const error = ref("");
const loading = ref(false);
const statusLabel = (status: string) => ({ valid: "确认有效", false_positive: "误报", fixed: "人工确认修复", ignored: "忽略" })[status] ?? status;
const load = async () => {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try { summary.value = await fetchFeedbackSummary(); }
  catch (reason) { error.value = getErrorMessage(reason, "反馈统计加载失败"); }
  finally { loading.value = false; }
};
onMounted(load);
</script>
