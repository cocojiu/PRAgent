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
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { canManage } from "@/stores/authState";
import { CheckCircle, Clock, GitPullRequestArrow, ListTodo, ShieldAlert, XCircle } from "lucide-vue-next";
import MetricGrid from "@/components/MetricGrid.vue";
import { retryReview } from "@/api/reviews";
import { useMetricIcon } from "@/composables/useMetricIcon";
import {
  canRetryReviewTask,
  ReviewTaskFilterBar,
  ReviewTaskPullRequestDialog,
  ReviewTaskTable,
  useReviewTaskCreation,
  useReviewTaskPullRequestPicker,
  useReviewTasksList
} from "@/features/review-tasks";
import type { ReviewTask } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const router = useRouter();
const createDialogVisible = ref(false);
const retryingTaskId = ref<number>();

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

const metricIconMap = {
  blue: ListTodo,
  red: ShieldAlert,
  orange: XCircle,
  green: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, CheckCircle);

onMounted(() => {
  initializeReviewTasksList();
  void loadPullRequests({ preselect: false });
});

const goDetail = (id: number) => router.push({ name: "task-detail", params: { id } });

const retryTask = async (task: ReviewTask) => {
  if (!canManage.value || !canRetryReviewTask(task) || retryingTaskId.value) {
    return;
  }
  try {
    const failureText = task.failureReason ? `\n\n失败原因：${task.failureReason}` : "";
    await ElMessageBox.confirm(`确认将 PR #${task.prNumber} 重新加入审查队列？${failureText}`, "确认重试审查任务", {
      confirmButtonText: "确认重试",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }

  retryingTaskId.value = task.id;
  try {
    const response = await retryReview(task.id);
    ElMessage.success(response.message || "审查任务已重新入队");
    await loadTasks();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "请求失败"));
  } finally {
    retryingTaskId.value = undefined;
  }
};

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
