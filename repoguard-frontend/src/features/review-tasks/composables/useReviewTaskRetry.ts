import type { Ref } from "vue";
import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { retryReview } from "@/api/reviews";
import type { ReviewTask } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { canRetryReviewTask } from "../reviewTaskDisplayMappers";

type UseReviewTaskRetryOptions = {
  canManage: Ref<boolean>;
  onRetried: () => Promise<void>;
};

export const useReviewTaskRetry = ({ canManage, onRetried }: UseReviewTaskRetryOptions) => {
  const retryingTaskId = ref<number>();

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
      await onRetried();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      retryingTaskId.value = undefined;
    }
  };

  return {
    retryingTaskId,
    retryTask
  };
};
