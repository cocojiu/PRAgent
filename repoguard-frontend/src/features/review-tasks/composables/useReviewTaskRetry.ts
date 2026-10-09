import type { Ref } from "vue";
import { getCurrentScope, onScopeDispose, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { retryReview } from "@/api/reviews";
import type { ReviewTask } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { canRetryReviewTask } from "../reviewTaskDisplayMappers";
import { activeTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";

type UseReviewTaskRetryOptions = {
  canManage: Ref<boolean>;
  getTask: (id: number) => ReviewTask | undefined;
  getContextKey: () => string;
  onRetried: () => Promise<void>;
};

export const useReviewTaskRetry = ({ canManage, getTask, getContextKey, onRetried }: UseReviewTaskRetryOptions) => {
  const retryingTaskId = ref<number>();
  let disposed = false;
  let revision = 0;
  const pendingTask = () => retryingTaskId.value === undefined ? undefined : getTask(retryingTaskId.value);
  watch([getContextKey, () => canManage.value, () => activeTenant.value, () => currentUser.value?.id,
    () => pendingTask()?.id, () => pendingTask()?.commit, () => pendingTask()?.status],
  () => { revision += 1; }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; revision += 1; });

  const retryTask = async (task: ReviewTask) => {
    const latest = getTask(task.id);
    if (disposed || !canManage.value || !latest || latest.commit !== task.commit
      || !canRetryReviewTask(latest) || retryingTaskId.value !== undefined) {
      return;
    }
    const taskId = latest.id; const commit = latest.commit;
    retryingTaskId.value = taskId;
    const version = revision;
    const current = () => !disposed && version === revision && canManage.value
      && getTask(taskId)?.commit === commit;
    try {
      const failureText = latest.failureReason ? `\n\n失败原因：${latest.failureReason}` : "";
      const superseded = latest.status === "superseded";
      const prompt = superseded
        ? `确认读取 PR #${task.prNumber} 的最新提交并重新审查？${failureText}`
        : `确认将 PR #${task.prNumber} 重新加入审查队列？${failureText}`;
      await ElMessageBox.confirm(prompt, superseded ? "确认按最新提交重评" : "确认重试审查任务", {
        confirmButtonText: superseded ? "确认重评" : "确认重试",
        cancelButtonText: "取消",
        type: "warning"
      });
      if (!current() || !canRetryReviewTask(getTask(taskId)!)) return;
      const response = await retryReview(taskId);
      if (!current()) return;
      if (response.taskId !== taskId) { ElMessage.warning("重试请求已返回，任务标识不一致，请刷新确认"); return; }
      const acknowledgement = response.message || (response.status === "publish_failed" ? "审查任务已保存，等待消息投递补偿" : "审查任务已重新入队");
      if (response.status === "publish_failed") ElMessage.warning(acknowledgement);
      else ElMessage.success(acknowledgement);
      try { await onRetried(); }
      catch (error) { if (current()) ElMessage.warning(`${acknowledgement}；列表刷新失败：${getErrorMessage(error)}`); }
    } catch (error) {
      if (current() && error !== "cancel" && error !== "close") ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      retryingTaskId.value = undefined;
    }
  };

  return {
    retryingTaskId,
    retryTask
  };
};
