import { computed, ref } from "vue";
import { describe, expect, it } from "vitest";
import { useReviewDetailDerivedCollections } from "./useReviewDetailDerivedCollections";
import type { ReviewTaskDetail } from "@/types";

describe("useReviewDetailDerivedCollections", () => {
  it("keeps complete file counts when findings pages change and preserves unknown legacy counts", () => {
    const selectedTask = ref({
      findings: [{ severity: "low", file: "src/A.java" }],
      changedFiles: [
        { path: "src/A.java", findingCount: 30 },
        { path: "src/B.java", findingCount: 0 },
        { path: "src/Legacy.java" }
      ],
      missingTests: [], timeline: []
    } as unknown as ReviewTaskDetail);
    const { changedFilesWithFindingCounts } = useReviewDetailDerivedCollections({
      failureReason: computed(() => ""), selectedTask
    });
    expect(changedFilesWithFindingCounts.value.map(file => file.findingCount)).toEqual([30, 0, undefined]);
    selectedTask.value = { ...selectedTask.value!, findings: [] };
    expect(changedFilesWithFindingCounts.value.map(file => file.findingCount)).toEqual([30, 0, undefined]);
  });

  it("uses aggregated severity counts instead of the current findings page", () => {
    const selectedTask = ref({
      findings: [
        { severity: "low", file: "src/App.java" }
      ],
      findingSeverityCounts: {
        critical: 1,
        high: 2,
        medium: 3,
        low: 24,
        info: 5
      },
      missingTests: [],
      changedFiles: [],
      timeline: []
    } as unknown as ReviewTaskDetail);

    const { findingCounts } = useReviewDetailDerivedCollections({
      failureReason: computed(() => ""),
      selectedTask
    });

    expect(findingCounts.value).toEqual({
      critical: 1,
      high: 2,
      medium: 3,
      low: 24,
      info: 5
    });
  });
});
