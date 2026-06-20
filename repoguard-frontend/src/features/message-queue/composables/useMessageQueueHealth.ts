import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { fetchMessageQueueHealth, requeueMessageQueueTask } from "@/api/messageQueue";
import type { MessageQueueExceptionTask, MessageQueueHealth } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const AUTO_REFRESH_INTERVAL_MS = 30000;

export const useMessageQueueHealth = () => {
  const loading = ref(false);
  const errorMessage = ref("");
  const health = ref<MessageQueueHealth>();
  const statusFilter = ref("");
  const repositoryFilter = ref("");
  const keyword = ref("");
  const autoRefresh = ref(false);
  const requeueingTaskId = ref<number>();
  let refreshTimer: ReturnType<typeof setInterval> | undefined;

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

  const loadHealth = async () => {
    loading.value = true;
    errorMessage.value = "";
    try {
      health.value = await fetchMessageQueueHealth();
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "消息队列健康数据加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      loading.value = false;
    }
  };

  const canRequeue = (status: MessageQueueExceptionTask["status"]) =>
    status === "PUBLISH_FAILED" || status === "RETRY_EXHAUSTED";

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

  const stopAutoRefresh = () => {
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = undefined;
    }
  };

  watch(autoRefresh, (enabled) => {
    stopAutoRefresh();
    if (enabled) {
      refreshTimer = setInterval(() => {
        void loadHealth();
      }, AUTO_REFRESH_INTERVAL_MS);
    }
  });

  onMounted(loadHealth);
  onBeforeUnmount(stopAutoRefresh);

  return {
    autoRefresh,
    errorMessage,
    filteredTasks,
    health,
    keyword,
    loading,
    repositoryFilter,
    repositories,
    requeueingTaskId,
    statusFilter,
    canRequeue,
    loadHealth,
    requeueTask
  };
};
