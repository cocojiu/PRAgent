<template>
  <div v-loading="loading" class="tasks-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>审查任务</h1>
        <p>查看和管理所有代码审查任务</p>
      </div>
      <el-button type="primary" @click="openCreateDialog">
        <GitPullRequestArrow :size="16" />
        新建审查任务
      </el-button>
    </div>

    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

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
        <el-button type="primary" plain :loading="loading" @click="refreshTasks">
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
        <el-table-column label="来源" width="130">
          <template #default="{ row }">
            <span :class="`source-pill ${sourceClass(row.triggerSource || row.source)}`">
              {{ sourceText(row.triggerSource || row.source) }}
            </span>
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
              <el-tooltip content="执行链路接口尚未接入">
                <span>
                  <el-button size="small" disabled>重试</el-button>
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无符合条件的审查任务" />
        </template>
      </el-table>

      <div class="table-footer">
        <span>共 {{ totalTasks }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          layout="sizes, prev, pager, next, jumper"
          :page-sizes="[5, 8, 10, 20]"
          :total="totalTasks"
        />
      </div>
    </section>

    <el-dialog v-model="createDialogVisible" title="选择 GitHub PR" width="760px" append-to-body destroy-on-close>
      <el-alert
        v-if="pullRequestError"
        class="page-alert"
        type="warning"
        :title="pullRequestError"
        show-icon
        :closable="false"
      />
      <div v-else class="pr-picker-meta">
        <Github :size="18" />
        <span>{{ pullRequestRepositoryText }}</span>
        <span v-if="pullRequestsLoaded" class="pr-picker-cache">已预加载 {{ pullRequestOptions.length }} 个 open PR</span>
        <el-button size="small" text :loading="loadingPullRequests" @click="reloadPullRequests">
          <RefreshCw :size="14" />
          刷新 PR
        </el-button>
      </div>
      <el-table
        v-loading="loadingPullRequests"
        :data="pullRequestOptions"
        class="rg-table"
        size="large"
        highlight-current-row
        aria-label="GitHub PR 列表"
        @current-change="selectPullRequest"
      >
        <el-table-column width="56">
          <template #default="{ row }">
            <el-radio v-model="selectedPullRequestNumber" :value="row.number" />
          </template>
        </el-table-column>
        <el-table-column label="PR" min-width="340">
          <template #default="{ row }">
            <div class="pr-option-cell">
              <strong>#{{ row.number }} {{ row.title }}</strong>
              <span>{{ row.author || "-" }} · {{ row.updatedAt || "-" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分支" min-width="160">
          <template #default="{ row }">
            <code>{{ row.branch || "-" }}</code>
          </template>
        </el-table-column>
        <el-table-column label="Commit" width="130">
          <template #default="{ row }">
            <code>{{ shortCommit(row.commit) }}</code>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前配置仓库暂无 open PR" />
        </template>
      </el-table>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingTask" :disabled="!selectedPullRequest" @click="createReviewFromSelectedPullRequest">
          创建审查任务
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { CheckCircle, Clock, Copy, Github, GitPullRequestArrow, ListTodo, RefreshCw, Search, ShieldAlert, XCircle } from "lucide-vue-next";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { fetchGithubPullRequestOptions, fetchReviews, triggerManualReview } from "@/api/reviews";
import { useMetricIcon } from "@/composables/useMetricIcon";
import type { GithubPullRequestOption, ReviewStatus, ReviewTask, RiskLevel } from "@/types";
import { riskText } from "@/utils/risk";
import { statusClass, statusText } from "@/utils/status";

const router = useRouter();
const loading = ref(false);
const errorMessage = ref("");
const reviewTasks = ref<ReviewTask[]>([]);
const allRepositories = ref<string[]>([]);
const pullRequestOptions = ref<GithubPullRequestOption[]>([]);
const totalTasks = ref(0);
const repoFilter = ref("");
const statusFilter = ref<ReviewStatus | "">("");
const riskFilter = ref<RiskLevel | "">("");
const keyword = ref("");
const currentPage = ref(1);
const pageSize = ref(8);
const createDialogVisible = ref(false);
const loadingPullRequests = ref(false);
const pullRequestsLoaded = ref(false);
const creatingTask = ref(false);
const pullRequestError = ref("");
const pullRequestOrganization = ref("");
const pullRequestRepository = ref("");
const selectedPullRequestNumber = ref<number>();
let filterDebounceTimer: ReturnType<typeof setTimeout> | undefined;
let taskRequestSeq = 0;
let pullRequestSeq = 0;

const metricIconMap = {
  blue: ListTodo,
  red: ShieldAlert,
  orange: XCircle,
  green: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

const repositories = computed(() => allRepositories.value);
const selectedPullRequest = computed(() =>
  pullRequestOptions.value.find((item) => item.number === selectedPullRequestNumber.value)
);
const pullRequestRepositoryText = computed(() => {
  if (!pullRequestOrganization.value || !pullRequestRepository.value) {
    return "使用集成配置中的 GitHub 仓库";
  }
  return `${pullRequestOrganization.value} / ${pullRequestRepository.value}`;
});

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
  const requestSeq = ++taskRequestSeq;
  loading.value = true;
  errorMessage.value = "";
  try {
    const page = await fetchReviews({
      page: currentPage.value,
      pageSize: pageSize.value,
      repository: repoFilter.value,
      status: statusFilter.value,
      riskLevel: riskFilter.value,
      keyword: keyword.value.trim()
    });
    if (requestSeq !== taskRequestSeq) {
      return;
    }
    reviewTasks.value = page.items;
    totalTasks.value = page.total;
  } catch (error) {
    if (requestSeq !== taskRequestSeq) {
      return;
    }
    errorMessage.value = error instanceof Error ? error.message : "审查任务加载失败";
    reviewTasks.value = [];
    totalTasks.value = 0;
  } finally {
    if (requestSeq === taskRequestSeq) {
      loading.value = false;
    }
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

const scheduleFilterLoad = () => {
  if (filterDebounceTimer) {
    clearTimeout(filterDebounceTimer);
  }
  filterDebounceTimer = setTimeout(() => {
    if (currentPage.value === 1) {
      void loadTasks();
    } else {
      resetPage();
    }
  }, 350);
};

watch([repoFilter, statusFilter, riskFilter, keyword], scheduleFilterLoad);

watch([currentPage, pageSize], () => {
  void loadTasks();
});

onMounted(() => {
  void loadRepositories();
  void loadTasks();
  void loadPullRequests({ preselect: false });
});

const goDetail = (id: number) => router.push({ name: "task-detail", params: { id } });
const refreshTasks = () => {
  if (filterDebounceTimer) {
    clearTimeout(filterDebounceTimer);
  }
  void loadTasks();
};

const shortCommit = (commit?: string) => (commit ? commit.slice(0, 7) : "-");

const openCreateDialog = () => {
  createDialogVisible.value = true;
  ensureDefaultPullRequestSelected();
  if (!pullRequestsLoaded.value && !loadingPullRequests.value) {
    void loadPullRequests();
  }
};

const ensureDefaultPullRequestSelected = () => {
  if (!selectedPullRequestNumber.value && pullRequestOptions.value.length) {
    selectedPullRequestNumber.value = pullRequestOptions.value[0].number;
  }
};

const loadPullRequests = async (options: { preselect?: boolean } = {}) => {
  const requestSeq = ++pullRequestSeq;
  loadingPullRequests.value = true;
  pullRequestError.value = "";
  try {
    const response = await fetchGithubPullRequestOptions();
    if (requestSeq !== pullRequestSeq) {
      return;
    }
    pullRequestOrganization.value = response.organization ?? "";
    pullRequestRepository.value = response.repository ?? "";
    pullRequestOptions.value = response.items;
    pullRequestsLoaded.value = true;
    if (options.preselect !== false) {
      ensureDefaultPullRequestSelected();
    }
  } catch (error) {
    if (requestSeq !== pullRequestSeq) {
      return;
    }
    pullRequestOptions.value = [];
    pullRequestsLoaded.value = false;
    pullRequestError.value = error instanceof Error ? error.message : "GitHub PR 列表加载失败";
  } finally {
    if (requestSeq === pullRequestSeq) {
      loadingPullRequests.value = false;
    }
  }
};

const reloadPullRequests = () => {
  selectedPullRequestNumber.value = undefined;
  void loadPullRequests();
};

const selectPullRequest = (row?: GithubPullRequestOption) => {
  selectedPullRequestNumber.value = row?.number;
};

const createReviewFromSelectedPullRequest = async () => {
  const pullRequest = selectedPullRequest.value;
  if (!pullRequest || !pullRequestOrganization.value || !pullRequestRepository.value) {
    ElMessage.warning("请选择一个有效的 GitHub PR");
    return;
  }
  creatingTask.value = true;
  try {
    const response = await triggerManualReview({
      organization: pullRequestOrganization.value,
      repository: pullRequestRepository.value,
      prNumber: pullRequest.number,
      title: pullRequest.title,
      commit: pullRequest.commit,
      branch: pullRequest.branch,
      source: "github_pr_picker"
    });
    createDialogVisible.value = false;
    if (response.existing) {
      ElMessage.info("该 PR commit 已有审查任务，已跳转到详情页");
    } else {
      ElMessage.success(response.message || "审查任务已创建");
    }
    await router.push({ name: "task-detail", params: { id: response.taskId } });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "审查任务创建失败");
  } finally {
    creatingTask.value = false;
  }
};

const sourceText = (source?: string) => {
  const labels: Record<string, string> = {
    manual_input: "手动输入",
    github_pr_picker: "PR 选择",
    existing_reused: "复用已有"
  };
  return source ? labels[source] ?? source : "手动输入";
};

const sourceClass = (source?: string) => {
  const classes: Record<string, string> = {
    manual_input: "manual",
    github_pr_picker: "github",
    existing_reused: "reused"
  };
  return source ? classes[source] ?? "manual" : "manual";
};
</script>
