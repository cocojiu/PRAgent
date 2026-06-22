import { computed } from "vue";
import {
  humanReviewPublishBlockReasonText,
  humanReviewStatusClass,
  humanReviewStatusText
} from "../reviewDetailDisplayMappers";
import type { Ref } from "vue";
import type { HumanReviewStatus, ReviewTaskDetail } from "@/types";

type UseReviewDetailHumanReviewDisplayOptions = {
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailHumanReviewDisplay = ({ selectedTask }: UseReviewDetailHumanReviewDisplayOptions) => {
  const humanReviewStatus = computed<HumanReviewStatus | string>(() => selectedTask.value?.humanReviewStatus ?? "not_required");

  const isHumanReviewPublishAllowed = computed(() => {
    if (!selectedTask.value?.humanReviewRequired) {
      return true;
    }
    return humanReviewStatus.value === "approved" || humanReviewStatus.value === "changes_requested";
  });

  const canSubmitHumanReview = computed(() =>
    Boolean(selectedTask.value?.humanReviewRequired && humanReviewStatus.value === "pending")
  );

  const humanReviewPublishBlockReason = computed(() => {
    return humanReviewPublishBlockReasonText(
      selectedTask.value?.humanReviewRequired,
      humanReviewStatus.value,
      isHumanReviewPublishAllowed.value
    );
  });

  const humanReviewStatusDisplayText = computed(() => {
    return humanReviewStatusText(humanReviewStatus.value);
  });

  const humanReviewStatusDisplayClass = computed(() => {
    return humanReviewStatusClass(humanReviewStatus.value);
  });

  return {
    canSubmitHumanReview,
    humanReviewPublishBlockReason,
    humanReviewStatus,
    humanReviewStatusClass: humanReviewStatusDisplayClass,
    humanReviewStatusText: humanReviewStatusDisplayText,
    isHumanReviewPublishAllowed
  };
};
