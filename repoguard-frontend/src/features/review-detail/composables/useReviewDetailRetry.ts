import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { retryReview } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type UseReviewDetailRetryOptions = {
  canManage: ComputedRef<boolean>;
  canRetryTask: ComputedRef<boolean>;
  clearGithubCommentState: () => void;
  failureReason: ComputedRef<string>;
  refreshDetail: () => Promise<void>;
  resetPollFailure: () => void;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailRetry = ({
  canManage,
  canRetryTask,
  clearGithubCommentState,
  failureReason,
  refreshDetail,
  resetPollFailure,
  selectedTask
}: UseReviewDetailRetryOptions) => {
  const retryingTask = ref(false);

  const confirmRetryReview = async () => {
    if (!canManage.value || !selectedTask.value || !canRetryTask.value || retryingTask.value) {
      return;
    }
    const taskId = selectedTask.value.id;
    const prNumber = selectedTask.value.prNumber;

    try {
      const failureText = failureReason.value ? `\n\n失败原因：${failureReason.value}` : "";
      await ElMessageBox.confirm(
        `确认将 PR #${prNumber} 重新加入审查队列？${failureText}`,
        "确认重试审查任务",
        {
          confirmButtonText: "确认重试",
          cancelButtonText: "取消",
          type: "warning"
        }
      );
    } catch {
      return;
    }

    retryingTask.value = true;
    try {
      const response = await retryReview(taskId);
      ElMessage.success(response.message || "审查任务已重新入队");
      clearGithubCommentState();
      resetPollFailure();
      await refreshDetail();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "请求失败"));
    } finally {
      retryingTask.value = false;
    }
  };

  return {
    confirmRetryReview,
    retryingTask
  };
};
