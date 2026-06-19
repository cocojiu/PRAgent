import { ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { submitHumanReview } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { HumanReviewRequest, ReviewStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type UseReviewDetailHumanReviewOptions = {
  canSubmitHumanReview: ComputedRef<boolean>;
  humanReviewActionText: (action: HumanReviewRequest["action"]) => string;
  refreshDetail: () => Promise<void>;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailHumanReview = ({
  canSubmitHumanReview,
  humanReviewActionText,
  refreshDetail,
  selectedTask
}: UseReviewDetailHumanReviewOptions) => {
  const submittingHumanReview = ref(false);

  const submitHumanReviewDecision = async (action: HumanReviewRequest["action"]) => {
    if (!selectedTask.value || !canSubmitHumanReview.value || submittingHumanReview.value) {
      return;
    }
    const taskId = selectedTask.value.id;
    try {
      const promptResult = await ElMessageBox.prompt(
        "请输入人工审查意见",
        humanReviewActionText(action),
        {
          confirmButtonText: "提交",
          cancelButtonText: "取消",
          inputType: "textarea",
          inputPlaceholder: action === "approve" ? "可选：记录通过原因" : "请说明需要修改或拒绝的原因",
          inputValidator: (value) => {
            if (action === "approve") {
              return true;
            }
            return Boolean(value?.trim()) || "请填写审查意见";
          }
        }
      );
      submittingHumanReview.value = true;
      const response = await submitHumanReview(taskId, {
        action,
        note: promptResult.value?.trim()
      });
      if (selectedTask.value) {
        selectedTask.value = {
          ...selectedTask.value,
          status: response.status as ReviewStatus,
          humanReviewRequired: response.humanReviewRequired,
          humanReviewStatus: response.humanReviewStatus,
          humanReviewNote: response.humanReviewNote,
          humanReviewBy: response.humanReviewBy,
          humanReviewedAt: response.humanReviewedAt
        };
      }
      ElMessage.success(humanReviewActionText(action));
      await refreshDetail();
    } catch (error) {
      if (error === "cancel" || error === "close") {
        return;
      }
      ElMessage.error(getErrorMessage(error, "人工审查提交失败"));
    } finally {
      submittingHumanReview.value = false;
    }
  };

  return {
    submittingHumanReview,
    submitHumanReviewDecision
  };
};
