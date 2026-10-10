import { getCurrentScope, onScopeDispose, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { updateFindingFeedback } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { FindingFeedbackResponse } from "@/api/generated/reviewDetailTypes";
import type { FindingFeedbackStatus, ReviewTaskDetail } from "@/types";
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
  let requestSequence = 0;
  let disposed = false;
  const cancelFindingFeedback = () => {
    requestSequence += 1;
    feedbackSavingId.value = null;
  };
  watch([() => selectedTask.value?.id, () => selectedTask.value?.commit, () => selectedTask.value?.archived, () => canManage.value],
    cancelFindingFeedback, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => {
    disposed = true;
    cancelFindingFeedback();
  });

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
              feedbackStatus: response.feedbackStatus ?? "unreviewed",
              feedbackNote: response.feedbackNote,
              feedbackBy: response.feedbackBy,
              feedbackAt: response.feedbackAt
            }
          : finding
      )
    };
  };

  const submitFindingFeedback = async (findingId: number, status: FindingFeedbackStatus) => {
    if (disposed || !selectedTask.value || selectedTask.value.archived || !canManage.value || feedbackSavingId.value !== null) {
      return;
    }
    const taskId = selectedTask.value.id;
    const commit = selectedTask.value.commit;
    const sequence = ++requestSequence;
    const current = () => !disposed && sequence === requestSequence && selectedTask.value?.id === taskId
      && selectedTask.value.commit === commit && !selectedTask.value.archived && canManage.value;
    feedbackSavingId.value = findingId;
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
      if (!current() || !selectedTask.value?.findings.some(finding => finding.id === findingId)) return;
      const response = await updateFindingFeedback(taskId, findingId, {
        status,
        note: promptResult.value?.trim()
      });
      if (!current()) return;
      if (response.taskId !== taskId || response.findingId !== findingId) {
        ElMessage.warning("判定已提交，返回结果与当前条目不一致，请刷新确认");
        return;
      }
      applyFindingFeedback(response);
      resetGithubCommentPublishResult();
      ElMessage.success(findingFeedbackPromptTitle(status));
      if (isTerminalTask.value) {
        try {
          await loadGithubCommentPreview(taskId);
        } catch (error) {
          if (current()) ElMessage.warning(`判定已提交，评论预览刷新失败：${getErrorMessage(error)}`);
        }
      }
    } catch (error) {
      if (!current() || error === "cancel" || error === "close") {
        return;
      }
      ElMessage.error(getErrorMessage(error, "判定提交失败"));
    } finally {
      if (sequence === requestSequence) feedbackSavingId.value = null;
    }
  };

  return {
    feedbackSavingId,
    cancelFindingFeedback,
    submitFindingFeedback
  };
};
