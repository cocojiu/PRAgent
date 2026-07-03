<template>
  <div v-loading="loading" class="tasks-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>审查任务</h1>
        <p>查看和管理所有代码审查任务</p>
      </div>
      <el-button type="primary" :disabled="!canManage" @click="openCreateDialog">
        <GitPullRequestArrow :size="16" />
        新建审查任务
      </el-button>
    </div>

    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="taskSummaryMetrics" :resolve-icon="getMetricIcon" />

    <section class="task-panel">
      <ReviewTaskFilterBar
        v-model:keyword="keyword"
        v-model:repository="repoFilter"
        v-model:risk="riskFilter"
        v-model:source="sourceFilter"
        v-model:status="statusFilter"
        :loading="loading"
        :repositories="repositories"
        @refresh="refreshTasks"
      />

      <ReviewTaskTable
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :can-manage="canManage"
        :retrying-task-id="retryingTaskId"
        :tasks="reviewTasks"
        :total="totalTasks"
        @retry="retryTask"
        @view="goDetail"
      />
    </section>

    <ReviewTaskPullRequestDialog
      v-model:visible="createDialogVisible"
      v-model:selected-pull-request-number="selectedPullRequestNumber"
      :can-create="canManage && !creatingTask && Boolean(selectedPullRequest)"
      :creating-task="creatingTask"
      :error="pullRequestError"
      :loading-pull-requests="loadingPullRequests"
      :pull-requests="pullRequestOptions"
      :pull-requests-loaded="pullRequestsLoaded"
      :repository-text="pullRequestRepositoryText"
      @create="createReviewFromSelectedPullRequest"
      @reload="reloadPullRequests"
      @select="selectPullRequest"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { canManage } from "@/stores/authState";
import { CheckCircle, Clock, GitPullRequestArrow, ListTodo, ShieldAlert, XCircle } from "lucide-vue-next";
import MetricGrid from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import {
  ReviewTaskFilterBar,
  ReviewTaskPullRequestDialog,
  ReviewTaskTable,
  useReviewTaskCreation,
  useReviewTaskPullRequestPicker,
  useReviewTaskRetry,
  useReviewTasksList
} from "@/features/review-tasks";

const router = useRouter();
const createDialogVisible = ref(false);

const {
  currentPage,
  errorMessage,
  keyword,
  loading,
  pageSize,
  repoFilter,
  repositories,
  reviewTasks,
  riskFilter,
  sourceFilter,
  statusFilter,
  taskSummaryMetrics,
  totalTasks,
  initializeReviewTasksList,
  loadTasks,
  refreshTasks
} = useReviewTasksList();

const {
  loadingPullRequests,
  pullRequestError,
  pullRequestOptions,
  pullRequestOrganization,
  pullRequestRepository,
  pullRequestRepositoryText,
  pullRequestsLoaded,
  selectedPullRequest,
  selectedPullRequestNumber,
  ensureDefaultPullRequestSelected,
  loadPullRequests,
  reloadPullRequests,
  selectPullRequest
} = useReviewTaskPullRequestPicker();

const { creatingTask, createReviewFromSelectedPullRequest } = useReviewTaskCreation({
  canManage,
  onCreated: async (taskId) => {
    createDialogVisible.value = false;
    await router.push({ name: "task-detail", params: { id: taskId } });
  },
  pullRequestOrganization,
  pullRequestRepository,
  selectedPullRequest
});

const { retryingTaskId, retryTask } = useReviewTaskRetry({
  canManage,
  onRetried: loadTasks
});

const metricIconMap = {
  blue: ListTodo,
  red: ShieldAlert,
  orange: XCircle,
  green: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

onMounted(() => {
  initializeReviewTasksList();
});

const goDetail = (id: number) => router.push({ name: "task-detail", params: { id } });

const openCreateDialog = () => {
  if (!canManage.value || creatingTask.value) {
    return;
  }
  createDialogVisible.value = true;
  ensureDefaultPullRequestSelected();
  if (!pullRequestsLoaded.value && !loadingPullRequests.value) {
    void loadPullRequests();
  }
};

</script>
