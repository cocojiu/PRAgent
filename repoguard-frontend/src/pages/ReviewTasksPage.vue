<template>
  <div class="tasks-page">
    <div class="page-heading">
      <h1>审查任务</h1>
      <p>查看和管理所有代码审查任务</p>
    </div>

    <section class="metric-grid">
      <div v-for="metric in taskMetrics" :key="metric.label" class="metric-card">
        <div class="metric-icon" :class="`metric-icon--${metric.color}`">
          <component :is="getMetricIcon(metric.color)" :size="30" />
        </div>
        <div>
          <p>{{ metric.label }}</p>
          <strong>{{ metric.value }}</strong>
          <span :class="metric.trendType === 'up-danger' ? 'trend danger' : 'trend'">
            较上周 {{ metric.trendType.includes("up") ? "↑" : "↓" }} {{ metric.trend }}
          </span>
        </div>
      </div>
    </section>

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

      <el-table :data="pagedTasks" class="rg-table task-table" size="large">
        <el-table-column label="PR" min-width="230">
          <template #default="{ row }">
            <div class="pr-cell">
              <Github :size="20" />
              <div>
                <RouterLink class="pr-link" :to="`/repoguard/tasks/${row.id}`">#{{ row.prNumber }}</RouterLink>
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
              <el-button size="small" :type="row.status === 'failed' ? 'primary' : 'default'">重试</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <span>共 {{ filteredTasks.length }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          layout="sizes, prev, pager, next, jumper"
          :page-sizes="[5, 8, 10, 20]"
          :total="filteredTasks.length"
          @size-change="handlePageSizeChange"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { CheckCircle, Clock, Copy, Github, ListTodo, RefreshCw, Search, ShieldAlert, XCircle } from "lucide-vue-next";
import { reviewTasks, taskMetrics } from "@/mocks/reviewTasks";
import type { ReviewStatus, RiskLevel } from "@/types";

const router = useRouter();
const repoFilter = ref("");
const statusFilter = ref("");
const riskFilter = ref("");
const keyword = ref("");
const currentPage = ref(1);
const pageSize = ref(8);

const metricIconMap = {
  blue: ListTodo,
  red: ShieldAlert,
  orange: XCircle,
  green: Clock
} as const;

const getMetricIcon = (color: string) => metricIconMap[color as keyof typeof metricIconMap] || CheckCircle;

const repositories = computed(() => Array.from(new Set(reviewTasks.map((task) => task.repository))));

const filteredTasks = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return reviewTasks.filter((task) => {
    const matchesRepo = !repoFilter.value || task.repository === repoFilter.value;
    const matchesStatus = !statusFilter.value || task.status === statusFilter.value;
    const matchesRisk = !riskFilter.value || task.riskLevel === riskFilter.value;
    const matchesKeyword =
      !query ||
      task.title.toLowerCase().includes(query) ||
      task.repository.toLowerCase().includes(query) ||
      task.organization.toLowerCase().includes(query) ||
      task.commit.toLowerCase().includes(query) ||
      String(task.prNumber).includes(query);
    return matchesRepo && matchesStatus && matchesRisk && matchesKeyword;
  });
});

const pagedTasks = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return filteredTasks.value.slice(start, start + pageSize.value);
});

watch([repoFilter, statusFilter, riskFilter, keyword], () => {
  currentPage.value = 1;
});

watch(filteredTasks, () => {
  const maxPage = Math.max(1, Math.ceil(filteredTasks.value.length / pageSize.value));
  if (currentPage.value > maxPage) {
    currentPage.value = maxPage;
  }
});

const riskText = (risk: RiskLevel) => ({ high: "高风险", medium: "中风险", low: "低风险", critical: "严重", info: "提示" })[risk];
const statusText = (status: ReviewStatus) => ({ completed: "已完成", reviewing: "审查中", failed: "失败", queued: "已入队" })[status];
const statusClass = (status: ReviewStatus) => ({ completed: "success", reviewing: "processing", failed: "danger", queued: "processing" })[status];

const goDetail = (id: number) => router.push(`/repoguard/tasks/${id}`);
const refreshTasks = () => {
  currentPage.value = 1;
};
const handlePageSizeChange = () => {
  currentPage.value = 1;
};
</script>
