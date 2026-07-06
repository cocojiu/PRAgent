import { computed, ref } from "vue";
import { describe, expect, it } from "vitest";
import { useReviewDetailDerivedCollections } from "./useReviewDetailDerivedCollections";
import type { ReviewTaskDetail } from "@/types";

describe("useReviewDetailDerivedCollections", () => {
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
