import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { fetchMessageQueueHealth, requeueMessageQueueTask } from "@/api/messageQueue";
import { useFilterPagination } from "@/composables/useFilterPagination";
import { createPageAwarePoller } from "@/composables/pageAwarePoller";
import { canRequeueMessageQueueStatus } from "@/features/message-queue/messageQueueDisplay";
import type { MessageQueueExceptionTask, MessageQueueHealth } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const AUTO_REFRESH_INTERVAL_MS = 30000;
const AUTO_REFRESH_MAX_INTERVAL_MS = 300000;
const DEFAULT_PAGE_SIZE = 10;

export const useMessageQueueHealth = () => {
  const loading = ref(false);
  const errorMessage = ref("");
  const health = ref<MessageQueueHealth>();
  const statusFilter = ref("");
  const repositoryFilter = ref("");
  const keyword = ref("");
  const autoRefresh = ref(false);
  const requeueingTaskId = ref<number>();
  const currentPage = ref(1);
  const pageSize = ref(DEFAULT_PAGE_SIZE);
  let autoRefreshFailureCount = 0;
  let healthRequest: { controller: AbortController; sequence: number } | undefined;
  let requestSequence = 0;

  const repositories = computed(() =>
    Array.from(new Set((health.value?.exceptionTasks ?? []).map((task) => task.repository).filter(Boolean) as string[])).sort()
  );

  const filteredTasks = computed(() => {
    const search = keyword.value.trim().toLowerCase();
    return (health.value?.exceptionTasks ?? []).filter((task) => {
      const matchesStatus = !statusFilter.value || task.status === statusFilter.value;
      const matchesRepo = !repositoryFilter.value || task.repository === repositoryFilter.value;
      const matchesKeyword = !search || [task.taskId, task.repository, task.organization, task.lastError, task.claimedBy]
        .filter((value) => value !== undefined && value !== null)
        .some((value) => String(value).toLowerCase().includes(search));
      return matchesStatus && matchesRepo && matchesKeyword;
    });
  });
  const { pagedItems: pagedTasks } = useFilterPagination({
    source: filteredTasks,
    filters: [statusFilter, repositoryFilter, keyword],
    currentPage,
    pageSize
  });

  const executeLoadHealth = async (background: boolean) => {
    if (background && healthRequest) {
      return;
    }
    healthRequest?.controller.abort();
    const request = {
      controller: new AbortController(),
      sequence: ++requestSequence
    };
    healthRequest = request;
    if (!background) {
      loading.value = true;
    }
    errorMessage.value = "";
    try {
      const response = await fetchMessageQueueHealth({ signal: request.controller.signal });
      if (request.sequence !== requestSequence) {
        return;
      }
      health.value = response;
      autoRefreshFailureCount = 0;
    } catch (error) {
      if (request.sequence !== requestSequence) {
        return;
      }
      if (background) {
        autoRefreshFailureCount += 1;
      }
      errorMessage.value = getErrorMessage(error, "消息队列健康数据加载失败");
      if (!background) {
        ElMessage.error(errorMessage.value);
      }
    } finally {
      if (request.sequence === requestSequence) {
        loading.value = false;
        healthRequest = undefined;
      }
    }
  };

  const loadHealth = () => executeLoadHealth(false);

  const canRequeue = (status: MessageQueueExceptionTask["status"]) => canRequeueMessageQueueStatus(status);

  const requeueTask = async (task: MessageQueueExceptionTask) => {
    if (!canRequeue(task.status) || requeueingTaskId.value) {
      return;
    }
    try {
      await ElMessageBox.confirm(
        `确认将任务 #${task.taskId} 重新发布到 RabbitMQ 队列？`,
        "确认重新入队",
        {
          confirmButtonText: "重新入队",
          cancelButtonText: "取消",
          type: "warning"
        }
      );
    } catch {
      return;
    }

    requeueingTaskId.value = task.taskId;
    try {
      const response = await requeueMessageQueueTask(task.taskId);
      if (response.status === "queued") {
        ElMessage.success("任务已重新入队");
      } else {
        ElMessage.warning(response.message || "任务仍等待发布补偿");
      }
      await loadHealth();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "消息队列操作失败"));
    } finally {
      requeueingTaskId.value = undefined;
    }
  };

  const autoRefreshPoller = createPageAwarePoller({
    intervalMs: () => Math.min(
      AUTO_REFRESH_INTERVAL_MS * 2 ** autoRefreshFailureCount,
      AUTO_REFRESH_MAX_INTERVAL_MS
    ),
    isEnabled: () => autoRefresh.value,
    poll: () => executeLoadHealth(true)
  });

  watch(autoRefresh, (enabled) => {
    if (enabled) {
      autoRefreshPoller.start();
    } else {
      autoRefreshFailureCount = 0;
      autoRefreshPoller.stop();
    }
  });

  onMounted(loadHealth);
  onBeforeUnmount(() => {
    autoRefreshPoller.dispose();
    healthRequest?.controller.abort();
    healthRequest = undefined;
    requestSequence += 1;
    loading.value = false;
  });

  return {
    autoRefresh,
    currentPage,
    errorMessage,
    filteredTasks,
    health,
    keyword,
    loading,
    pageSize,
    pagedTasks,
    repositoryFilter,
    repositories,
    requeueingTaskId,
    statusFilter,
    canRequeue,
    loadHealth,
    requeueTask
  };
};
