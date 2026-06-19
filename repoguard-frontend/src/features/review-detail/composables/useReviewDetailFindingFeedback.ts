import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { updateFindingFeedback } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { FindingFeedbackResponse, FindingFeedbackStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type UseReviewDetailFindingFeedbackOptions = {
  canManage: ComputedRef<boolean>;
  findingFeedbackPromptTitle: (status: FindingFeedbackStatus) => string;
  isTerminalTask: ComputedRef<boolean>;
  loadGithubCommentPreview: (id: number) => Promise<void>;
  resetGithubCommentPublishResult: () => void;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailFindingFeedback = ({
  canManage,
  findingFeedbackPromptTitle,
  isTerminalTask,
  loadGithubCommentPreview,
  resetGithubCommentPublishResult,
  selectedTask
}: UseReviewDetailFindingFeedbackOptions) => {
  const feedbackSavingId = ref<number | null>(null);

  const applyFindingFeedback = (response: FindingFeedbackResponse) => {
    if (!selectedTask.value) {
      return;
    }
    selectedTask.value = {
      ...selectedTask.value,
      findings: selectedTask.value.findings.map((finding) =>
        finding.id === response.findingId
          ? {
              ...finding,
              feedbackStatus: response.feedbackStatus,
              feedbackNote: response.feedbackNote,
              feedbackBy: response.feedbackBy,
              feedbackAt: response.feedbackAt
            }
          : finding
      )
    };
  };

  const submitFindingFeedback = async (findingId: number, status: FindingFeedbackStatus) => {
    if (!selectedTask.value || !canManage.value || feedbackSavingId.value) {
      return;
    }
    const taskId = selectedTask.value.id;
    try {
      const promptResult = await ElMessageBox.prompt(
        "请输入判定备注",
        findingFeedbackPromptTitle(status),
        {
          confirmButtonText: "提交",
          cancelButtonText: "取消",
          inputType: "textarea",
          inputPlaceholder: status === "valid" || status === "fixed" ? "可选：记录确认依据" : "请说明判定原因",
          inputValidator: (value) => {
            if (status === "valid" || status === "fixed") {
              return true;
            }
            return Boolean(value?.trim()) || "请填写判定原因";
          }
        }
      );
      feedbackSavingId.value = findingId;
      const response = await updateFindingFeedback(taskId, findingId, {
        status,
        note: promptResult.value?.trim()
      });
      applyFindingFeedback(response);
      resetGithubCommentPublishResult();
      if (isTerminalTask.value) {
        await loadGithubCommentPreview(taskId);
      }
      ElMessage.success(findingFeedbackPromptTitle(status));
    } catch (error) {
      if (error === "cancel" || error === "close") {
        return;
      }
      ElMessage.error(getErrorMessage(error, "判定提交失败"));
    } finally {
      feedbackSavingId.value = null;
    }
  };

  return {
    feedbackSavingId,
    submitFindingFeedback
  };
};
