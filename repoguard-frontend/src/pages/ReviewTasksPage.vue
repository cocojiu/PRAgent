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
      <el-tabs
        :model-value="statusFilter === 'pending_human_review' ? 'pending' : 'all'"
        aria-label="审查任务视图"
        @update:model-value="statusFilter = $event === 'pending' ? 'pending_human_review' : ''"
      >
        <el-tab-pane label="全部任务" name="all" />
        <el-tab-pane label="待人工复核" name="pending" />
      </el-tabs>
      <el-alert
        v-if="statusFilter === 'pending_human_review'"
        title="仅显示当前仍需人工决定的任务；点击详情进行复核或 Finding 反馈。此列表不执行领取、分派或批量操作。"
        type="info"
        :closable="false"
        show-icon
      />
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
import "@/features/review-tasks/reviewTasks.css";
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useRoute } from "vue-router";
import { canManage } from "@/stores/authState";
import { CheckCircle, Clock, GitPullRequestArrow, ListTodo, ShieldAlert, XCircle } from "@lucide/vue";
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
const route = useRoute();
const createDialogVisible = ref(false);
let onboardingPreviewOpened = false;

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
} = useReviewTasksList(route.query.review === "pending" ? "pending_human_review" : "");

// Preserve the queue entry when returning from a task detail page or reloading.
watch(statusFilter, value => {
  const query = { ...route.query };
  if (value === "pending_human_review") {
    query.review = "pending";
  } else {
    delete query.review;
  }
  void router.replace({ query });
});

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
  tryOpenOnboardingPreview();
});

watch(canManage, () => {
  tryOpenOnboardingPreview();
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

const tryOpenOnboardingPreview = () => {
  if (onboardingPreviewOpened || route.query.onboarding !== "preview" || !canManage.value) {
    return;
  }
  onboardingPreviewOpened = true;
  openCreateDialog();
};

</script>
