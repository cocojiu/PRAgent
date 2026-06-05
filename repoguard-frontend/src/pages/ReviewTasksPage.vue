<template>
  <div v-loading="loading" class="tasks-page">
    <div class="page-heading">
      <h1>审查任务</h1>
      <p>查看和管理所有代码审查任务</p>
    </div>

    <MetricGrid :metrics="taskSummaryMetrics" :resolve-icon="getMetricIcon" />

    <section class="task-panel">
      <div class="filter-bar">
        <el-select v-model="repoFilter" placeholder="全部仓库" clearable>
          <el-option label="全部仓库" value="" />
          <el-option v-for="repo in repositories" :key="repo" :label="repo" :value="repo" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable>
          <el-option label="全部状态" value="" />
          <el-option label="已完成" value="completed" />
          <el-option label="审查中" value="reviewing" />
          <el-option label="失败" value="failed" />
        </el-select>
        <el-select v-model="riskFilter" placeholder="全部风险等级" clearable>
          <el-option label="全部风险等级" value="" />
          <el-option label="高风险" value="high" />
          <el-option label="中风险" value="medium" />
          <el-option label="低风险" value="low" />
        </el-select>
        <el-input v-model="keyword" class="search-input" placeholder="搜索 PR 标题、作者或 Commit ID" clearable>
          <template #suffix><Search :size="18" /></template>
        </el-input>
        <el-button type="primary" plain @click="refreshTasks">
          <RefreshCw :size="16" />
          刷新
        </el-button>
      </div>

      <el-table :data="reviewTasks" class="rg-table task-table" size="large" aria-label="审查任务列表">
        <el-table-column label="PR" min-width="230">
          <template #default="{ row }">
            <div class="pr-cell">
              <Github :size="20" />
              <div>
                <RouterLink class="pr-link" :to="{ name: 'task-detail', params: { id: row.id } }">
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
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span :class="`status-pill ${statusClass(row.status)}`">{{ statusText(row.status) }}</span>
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
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="goDetail(row.id)">查看</el-button>
              <el-button size="small" @click="retryTask(row.id)">重试</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <span>共 {{ totalTasks }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          layout="sizes, prev, pager, next, jumper"
          :page-sizes="[5, 8, 10, 20]"
          :total="totalTasks"
          @size-change="resetPage"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { CheckCircle, Clock, Copy, Github, ListTodo, RefreshCw, Search, ShieldAlert, XCircle } from "lucide-vue-next";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { fetchReviews } from "@/api/reviews";
import { useMetricIcon } from "@/composables/useMetricIcon";
import type { ReviewStatus, ReviewTask, RiskLevel } from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

const router = useRouter();
const loading = ref(false);
const reviewTasks = ref<ReviewTask[]>([]);
const allRepositories = ref<string[]>([]);
const totalTasks = ref(0);
const repoFilter = ref("");
const statusFilter = ref<ReviewStatus | "">("");
const riskFilter = ref<RiskLevel | "">("");
const keyword = ref("");
const currentPage = ref(1);
const pageSize = ref(8);

const metricIconMap = {
  blue: ListTodo,
  red: ShieldAlert,
  orange: XCircle,
  green: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

const repositories = computed(() => allRepositories.value);

const parseDurationSeconds = (duration: string) => {
  const [minutes = 0, seconds = 0] = duration.match(/\d+/g)?.map(Number) ?? [];
  return minutes * 60 + seconds;
};

const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60);
  const restSeconds = seconds % 60;
  return `${minutes} 分 ${restSeconds} 秒`;
};

const taskSummaryMetrics = computed<MetricGridItem[]>(() => {
  const tasks = reviewTasks.value;
  const total = tasks.length;
  const highRiskCount = tasks.filter((task) => task.riskLevel === "high" || task.riskLevel === "critical").length;
  const failedCount = tasks.filter((task) => task.status === "failed").length;
  const avgSeconds = total
    ? Math.round(tasks.reduce((sum, task) => sum + parseDurationSeconds(task.duration), 0) / total)
    : 0;

  return [
    { label: "本周审查", value: String(total), note: "当前筛选结果", noteClass: "trend", color: "blue" },
    {
      label: "高风险 PR",
      value: String(highRiskCount),
      note: `${total ? Math.round((highRiskCount / total) * 100) : 0}% 占比`,
      noteClass: "trend danger",
      color: "red"
    },
    {
      label: "失败任务",
      value: String(failedCount),
      note: `${total ? Math.round((failedCount / total) * 100) : 0}% 占比`,
      noteClass: "trend danger",
      color: "orange"
    },
    { label: "平均耗时", value: formatDuration(avgSeconds), note: "按当前结果计算", noteClass: "trend", color: "green" }
  ];
});

const loadTasks = async () => {
  loading.value = true;
  try {
    const page = await fetchReviews({
      page: currentPage.value,
      pageSize: pageSize.value,
      repository: repoFilter.value,
      status: statusFilter.value,
      riskLevel: riskFilter.value,
      keyword: keyword.value.trim()
    });
    reviewTasks.value = page.items;
    totalTasks.value = page.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "审查任务加载失败");
  } finally {
    loading.value = false;
  }
};

const loadRepositories = async () => {
  try {
    const page = await fetchReviews({ page: 1, pageSize: 100 });
    allRepositories.value = Array.from(new Set(page.items.map((task) => task.repository))).sort();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "仓库筛选项加载失败");
  }
};

const resetPage = () => {
  currentPage.value = 1;
};

watch([repoFilter, statusFilter, riskFilter, keyword], () => {
  resetPage();
  void loadTasks();
});

watch([currentPage, pageSize], () => {
  void loadTasks();
});

onMounted(() => {
  void loadRepositories();
  void loadTasks();
});

const goDetail = (id: number) => router.push({ name: "task-detail", params: { id } });
const retryTask = (id: number) => {
  ElMessage.success(`任务 #${id} 已重新入队`);
};
const refreshTasks = () => {
  void loadTasks();
};
</script>
