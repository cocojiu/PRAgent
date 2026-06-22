import { computed } from "vue";
import {
  llmCostText,
  llmDurationText,
  llmModelText,
  llmParseStatusClass,
  llmParseStatusText,
  llmTokenUsageText
} from "../reviewDetailDisplayMappers";
import type { Ref } from "vue";
import type { ReviewTaskDetail } from "@/types";

type UseReviewDetailLlmDisplayOptions = {
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailLlmDisplay = ({ selectedTask }: UseReviewDetailLlmDisplayOptions) => {
  const llmModelDisplayText = computed(() => {
    return selectedTask.value ? llmModelText(selectedTask.value.llm) : "-";
  });

  const llmDurationDisplayText = computed(() => {
    return selectedTask.value ? llmDurationText(selectedTask.value.llm) : "-";
  });

  const llmTokenUsageDisplayText = computed(() => {
    return llmTokenUsageText(selectedTask.value?.llm);
  });

  const llmCostDisplayText = computed(() => {
    return llmCostText(selectedTask.value?.llm);
  });

  const llmParseStatusDisplayText = computed(() => {
    return llmParseStatusText(selectedTask.value?.llm);
  });

  const llmParseStatusDisplayClass = computed(() => {
    return llmParseStatusClass(selectedTask.value?.llm);
  });

  return {
    llmCostText: llmCostDisplayText,
    llmDurationText: llmDurationDisplayText,
    llmModelText: llmModelDisplayText,
    llmParseStatusClass: llmParseStatusDisplayClass,
    llmParseStatusText: llmParseStatusDisplayText,
    llmTokenUsageText: llmTokenUsageDisplayText
  };
};
