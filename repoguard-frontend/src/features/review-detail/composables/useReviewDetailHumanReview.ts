import { getCurrentScope, onScopeDispose, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ElMessageBox } from "element-plus/es/components/message-box/index.mjs";
import { submitHumanReview } from "@/api/reviews";
import type { ComputedRef, Ref } from "vue";
import type { HumanReviewRequest } from "@/api/generated/reviewDetailTypes";
import type { ReviewStatus, ReviewTaskDetail } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { activeTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";

type UseReviewDetailHumanReviewOptions = {
  canManage: Ref<boolean>;
  getTaskId: () => number;
  canSubmitHumanReview: ComputedRef<boolean>;
  humanReviewActionText: (action: HumanReviewRequest["action"]) => string;
  refreshDetail: () => Promise<void>;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailHumanReview = ({
  canManage,
  getTaskId,
  canSubmitHumanReview,
  humanReviewActionText,
  refreshDetail,
  selectedTask
}: UseReviewDetailHumanReviewOptions) => {
  const submittingHumanReview = ref(false);
  let disposed = false;
  let revision = 0;
  watch([getTaskId, () => selectedTask.value?.id, () => selectedTask.value?.commit,
    () => selectedTask.value?.archived, () => canManage.value, () => canSubmitHumanReview.value,
    () => activeTenant.value, () => currentUser.value?.id], () => { revision += 1; }, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; revision += 1; });

  const submitHumanReviewDecision = async (action: HumanReviewRequest["action"]) => {
    if (disposed || !selectedTask.value || selectedTask.value.archived || !canManage.value
      || !canSubmitHumanReview.value || submittingHumanReview.value || getTaskId() !== selectedTask.value.id) {
      return;
    }
    const taskId = selectedTask.value.id;
    const commit = selectedTask.value.commit;
    let version = revision;
    const current = () => !disposed && version === revision && canManage.value && getTaskId() === taskId
      && selectedTask.value?.id === taskId && selectedTask.value.commit === commit && !selectedTask.value.archived;
    submittingHumanReview.value = true;
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
      if (!current() || !canSubmitHumanReview.value) return;
      const response = await submitHumanReview(taskId, {
        action,
        note: promptResult.value?.trim()
      });
      if (!current() || !canSubmitHumanReview.value) return;
      if (response.taskId !== taskId) {
        ElMessage.warning("人工审查请求已返回，任务标识不一致，请刷新确认"); return;
      }
      if (selectedTask.value) {
        selectedTask.value = {
          ...selectedTask.value,
          status: response.status ? response.status as ReviewStatus : selectedTask.value.status,
          humanReviewRequired: response.humanReviewRequired ?? selectedTask.value.humanReviewRequired,
          humanReviewStatus: response.humanReviewStatus ?? selectedTask.value.humanReviewStatus,
          humanReviewNote: response.humanReviewNote,
          humanReviewBy: response.humanReviewBy,
          humanReviewedAt: response.humanReviewedAt
        };
      }
      version = revision;
      ElMessage.success(humanReviewActionText(action));
      try { await refreshDetail(); }
      catch (error) { if (current()) ElMessage.warning(`人工审查已提交，详情刷新失败：${getErrorMessage(error)}`); }
    } catch (error) {
      if (!current() || error === "cancel" || error === "close") {
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
