import type { ReviewStatus, ReviewTaskDetail, ReviewTaskStatus, RiskLevel, TimelineItem } from "@/types";

export const normalizeReviewTaskDetail = (task: ReviewTaskDetail): ReviewTaskDetail => ({
  ...task,
  status: task.status as ReviewStatus,
  llmStatus: task.llmStatus as ReviewStatus,
  humanReviewRequired: Boolean(task.humanReviewRequired),
  humanReviewStatus: task.humanReviewStatus ?? "not_required",
  findingTotal: task.findingTotal ?? task.findings?.length ?? 0,
  missingTestTotal: task.missingTestTotal ?? task.missingTests?.length ?? 0,
  changedFileTotal: task.changedFileTotal ?? task.changedFiles?.length ?? 0,
  riskProfile: {
    score: task.riskProfile?.score ?? 0,
    level: (task.riskProfile?.level ?? "info") as RiskLevel,
    summary: task.riskProfile?.summary ?? "暂无风险画像数据。",
    recommendHumanReview: Boolean(task.riskProfile?.recommendHumanReview),
    humanReviewReason: task.riskProfile?.humanReviewReason ?? "可按常规流程推进。",
    signals: task.riskProfile?.signals ?? [],
    highRiskFiles: task.riskProfile?.highRiskFiles ?? []
  },
  prSummary: {
    overallRisk: task.prSummary?.overallRisk ?? task.riskProfile?.level ?? "info",
    summary: task.prSummary?.summary ?? "暂无 PR 总评数据。",
    mergeRecommendation: task.prSummary?.mergeRecommendation ?? "可按团队流程继续复核。",
    recommendMerge: Boolean(task.prSummary?.recommendMerge),
    humanReviewRequired: Boolean(task.prSummary?.humanReviewRequired),
    keyRisks: task.prSummary?.keyRisks ?? [],
    focusFiles: task.prSummary?.focusFiles ?? [],
    githubCommentBody: task.prSummary?.githubCommentBody ?? ""
  },
  llm: {
    ...task.llm,
    status: task.llm.status as ReviewStatus,
    promptTokens: task.llm.promptTokens ?? 0,
    completionTokens: task.llm.completionTokens ?? 0,
    totalTokens: task.llm.totalTokens ?? 0,
    estimatedCost: task.llm.estimatedCost ?? ""
  },
  chunkedReview: {
    enabled: Boolean(task.chunkedReview?.enabled),
    chunkCount: task.chunkedReview?.chunkCount ?? 0,
    aggregateRisk: task.chunkedReview?.aggregateRisk ?? "info",
    aggregateFindings: task.chunkedReview?.aggregateFindings ?? 0,
    failedChunks: task.chunkedReview?.failedChunks ?? 0,
    reasons: task.chunkedReview?.reasons ?? []
  }
});

const normalizeTimelineItem = (item: TimelineItem): TimelineItem => ({
  ...item,
  status: item.status as TimelineItem["status"]
});

const mergeLatestTimeline = (timeline: TimelineItem[], latestTimeline?: TimelineItem): TimelineItem[] => {
  if (!latestTimeline) {
    return timeline;
  }
  const latest = normalizeTimelineItem(latestTimeline);
  const normalizedTimeline = timeline.map((item) =>
    latest.status === "current" && item.status === "current" && item.label !== latest.label
      ? { ...item, status: "done" as TimelineItem["status"] }
      : item
  );
  const existingIndex = normalizedTimeline.findIndex((item) => item.label === latest.label && item.time === latest.time);
  if (existingIndex >= 0) {
    return normalizedTimeline.map((item, index) => (index === existingIndex ? latest : item));
  }
  return [...normalizedTimeline, latest];
};

export const applyReviewStatusSnapshot = (
  task: ReviewTaskDetail,
  status: ReviewTaskStatus
): ReviewTaskDetail => {
  const normalizedStatus = status.status as ReviewStatus;
  const normalizedLlmStatus = status.llmStatus as ReviewStatus;
  const normalizedRiskLevel = status.riskLevel as RiskLevel;
  return {
    ...task,
    status: normalizedStatus,
    riskLevel: normalizedRiskLevel,
    llmStatus: normalizedLlmStatus,
    duration: status.duration,
    failureCategory: status.failureCategory,
    failureReason: status.failureReason,
    failureSuggestion: status.failureSuggestion,
    humanReviewRequired: status.humanReviewRequired,
    humanReviewStatus: status.humanReviewStatus,
    humanReviewNote: status.humanReviewNote,
    humanReviewBy: status.humanReviewBy,
    humanReviewedAt: status.humanReviewedAt,
    timeline: mergeLatestTimeline(task.timeline, status.latestTimeline),
    llm: {
      ...task.llm,
      status: normalizedLlmStatus,
      duration: status.duration,
      riskLevel: normalizedRiskLevel
    }
  };
};
