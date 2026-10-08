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
      <div class="feedback-filters" role="group" aria-label="反馈明细筛选">
        <el-select v-model="repositoryFilter" clearable placeholder="全部仓库" aria-label="按仓库筛选反馈">
          <el-option v-for="repository in repositories" :key="repository" :label="repository" :value="repository" />
        </el-select>
        <el-select v-model="sourceFilter" clearable placeholder="全部来源" aria-label="按来源筛选反馈">
          <el-option v-for="source in sources" :key="source" :label="source" :value="source" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="全部反馈" aria-label="按反馈类型筛选">
          <el-option v-for="status in statuses" :key="status" :label="statusLabel(status)" :value="status" />
        </el-select>
        <el-button :disabled="!hasFilters" @click="clearFilters">清除筛选</el-button>
      </div>
      <p aria-live="polite">当前明细 {{ summary.details.length }} 条，匹配 {{ filteredDetails.length }} 条。筛选仅作用于这些明细，上方来源统计保持原观察范围。</p>
      <el-table :data="filteredDetails" row-key="findingId" aria-label="反馈明细">
        <el-table-column prop="repository" label="仓库" />
        <el-table-column prop="prNumber" label="PR" width="80" />
        <el-table-column prop="source" label="来源" width="90" />
        <el-table-column label="反馈"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="actor" label="确认人" />
        <el-table-column prop="feedbackAt" label="确认时间" />
        <el-table-column label="追溯">
          <template #default="{ row }"><router-link :to="`/repoguard/tasks/${row.taskId}`">任务 {{ row.taskId }} · Finding {{ row.findingId }}</router-link></template>
        </el-table-column>
        <template #empty><el-empty :description="summary.details.length ? '当前明细中没有匹配项，可清除筛选查看其他反馈' : '暂无可追溯人工反馈，数据不足'" /></template>
      </el-table>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchFeedbackSummary } from "@/api/config";
import type { FeedbackSummary } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const summary = ref<FeedbackSummary | null>(null);
const error = ref("");
const loading = ref(false);
const repositoryFilter = ref("");
const sourceFilter = ref("");
const statusFilter = ref("");
const details = computed(() => summary.value?.details ?? []);
const repositories = computed(() => [...new Set(details.value.map(row => row.repository))].sort());
const sources = computed(() => [...new Set(details.value.map(row => row.source))].sort());
const statuses = ["valid", "false_positive", "fixed", "ignored"];
const hasFilters = computed(() => Boolean(repositoryFilter.value || sourceFilter.value || statusFilter.value));
const filteredDetails = computed(() => details.value.filter(row =>
  (!repositoryFilter.value || row.repository === repositoryFilter.value)
  && (!sourceFilter.value || row.source === sourceFilter.value)
  && (!statusFilter.value || row.status === statusFilter.value)
));
const clearFilters = () => {
  repositoryFilter.value = "";
  sourceFilter.value = "";
  statusFilter.value = "";
};
const statusLabel = (status: string) => ({ valid: "确认有效", false_positive: "误报", fixed: "人工确认修复", ignored: "忽略" })[status] ?? status;
const load = async () => {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  summary.value = null;
  try { summary.value = await fetchFeedbackSummary(); }
  catch (reason) { error.value = getErrorMessage(reason, "反馈统计加载失败"); }
  finally { loading.value = false; }
};
onMounted(load);
</script>

<style scoped>
.feedback-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.feedback-filters .el-select {
  width: 220px;
  max-width: 100%;
}
</style>
