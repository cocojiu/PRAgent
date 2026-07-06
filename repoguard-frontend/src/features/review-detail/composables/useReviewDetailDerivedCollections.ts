import { computed } from "vue";
import { statusReasonText, timelineLabelText } from "../reviewDetailDisplayMappers";
import type { ComputedRef, Ref } from "vue";
import type { ChangedFile, ReviewTaskDetail, RiskLevel, TimelineItem } from "@/types";

export type ChangedFileWithFindingCount = ChangedFile & { findingCount: number };

type UseReviewDetailDerivedCollectionsOptions = {
  failureReason: ComputedRef<string>;
  selectedTask: Ref<ReviewTaskDetail | null>;
};

export const useReviewDetailDerivedCollections = ({
  failureReason,
  selectedTask
}: UseReviewDetailDerivedCollectionsOptions) => {
  const reviewFindings = computed(() => selectedTask.value?.findings ?? []);
  const missingTests = computed(() => selectedTask.value?.missingTests ?? []);
  const changedFiles = computed(() => selectedTask.value?.changedFiles ?? []);
  const reviewTimeline = computed(() => selectedTask.value?.timeline ?? []);
  const riskProfile = computed(() => selectedTask.value?.riskProfile);

  const findingCounts = computed<Record<RiskLevel, number>>(() => {
    const severityCounts = selectedTask.value?.findingSeverityCounts;
    if (severityCounts) {
      return {
        critical: severityCounts.critical ?? 0,
        high: severityCounts.high ?? 0,
        medium: severityCounts.medium ?? 0,
        low: severityCounts.low ?? 0,
        info: severityCounts.info ?? 0
      };
    }
    return reviewFindings.value.reduce(
      (counts, finding) => {
        counts[finding.severity] += 1;
        return counts;
      },
      { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
    );
  });

  const findingCountByFile = computed(() =>
    reviewFindings.value.reduce<Record<string, number>>((counts, finding) => {
      counts[finding.file] = (counts[finding.file] ?? 0) + 1;
      return counts;
    }, {})
  );

  const changedFilesWithFindingCounts = computed<ChangedFileWithFindingCount[]>(() =>
    changedFiles.value.map((file) => ({
      ...file,
      findingCount: findingCountByFile.value[file.path] ?? 0
    }))
  );

  const localizedTimeline = computed<TimelineItem[]>(() =>
    reviewTimeline.value.map((item) => ({
      ...item,
      label: timelineLabelText(item.label)
    }))
  );

  const statusReason = computed(() => {
    return statusReasonText(failureReason.value, reviewTimeline.value);
  });

  return {
    changedFiles,
    changedFilesWithFindingCounts,
    findingCounts,
    localizedTimeline,
    missingTests,
    reviewFindings,
    reviewTimeline,
    riskProfile,
    statusReason
  };
};
