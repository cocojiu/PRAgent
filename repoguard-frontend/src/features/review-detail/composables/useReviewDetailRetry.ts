import { getCurrentScope, onScopeDispose, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { retryReview } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { activeTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";

type UseReviewDetailRetryOptions = {
  canManage: ComputedRef<boolean>;
  canRetryTask: ComputedRef<boolean>;
  getTaskId: () => number;
  clearGithubCommentState: () => void;
  failureReason: ComputedRef<string>;
  refreshDetail: () => Promise<void>;
  resetPollFailure: () => void;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailRetry = ({
  canManage,
  canRetryTask,
  getTaskId,
  clearGithubCommentState,
  failureReason,
  refreshDetail,
  resetPollFailure,
  selectedTask
}: UseReviewDetailRetryOptions) => {
  const retryingTask = ref(false);
  let disposed = false;
  let revision = 0;
  watch([getTaskId, () => selectedTask.value?.id, () => selectedTask.value?.commit,
    () => selectedTask.value?.archived, () => selectedTask.value?.status, () => canManage.value,
    () => canRetryTask.value, () => activeTenant.value, () => currentUser.value?.id],
  () => { revision += 1; }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; revision += 1; });

  const confirmRetryReview = async () => {
    if (disposed || !canManage.value || !selectedTask.value || selectedTask.value.archived
      || !canRetryTask.value || retryingTask.value || getTaskId() !== selectedTask.value.id) {
      return;
    }
    const taskId = selectedTask.value.id;
    const prNumber = selectedTask.value.prNumber;
    const commit = selectedTask.value.commit;
    const version = revision;
    const current = () => !disposed && version === revision && canManage.value && getTaskId() === taskId
      && selectedTask.value?.id === taskId && selectedTask.value.commit === commit && !selectedTask.value.archived;
    retryingTask.value = true;

    try {
      const failureText = failureReason.value ? `\n\n失败原因：${failureReason.value}` : "";
      const superseded = selectedTask.value.status === "superseded";
      await ElMessageBox.confirm(
        superseded
          ? `确认读取 PR #${prNumber} 的最新提交并重新审查？${failureText}`
          : `确认将 PR #${prNumber} 重新加入审查队列？${failureText}`,
        superseded ? "确认按最新提交重评" : "确认重试审查任务",
        {
          confirmButtonText: superseded ? "确认重评" : "确认重试",
          cancelButtonText: "取消",
          type: "warning"
        }
      );
      if (!current() || !canRetryTask.value) return;
      const response = await retryReview(taskId);
      if (!current()) return;
      if (response.taskId !== taskId) { ElMessage.warning("重试请求已返回，任务标识不一致，请刷新确认"); return; }
      const acknowledgement = response.message || (response.status === "publish_failed" ? "审查任务已保存，等待消息投递补偿" : "审查任务已重新入队");
      if (response.status === "publish_failed") ElMessage.warning(acknowledgement);
      else ElMessage.success(acknowledgement);
      clearGithubCommentState();
      resetPollFailure();
      try { await refreshDetail(); }
      catch (error) { if (current()) ElMessage.warning(`${acknowledgement}；详情刷新失败：${getErrorMessage(error)}`); }
    } catch (error) {
      if (current() && error !== "cancel" && error !== "close") ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      retryingTask.value = false;
    }
  };

  return {
    confirmRetryReview,
    retryingTask
  };
};
