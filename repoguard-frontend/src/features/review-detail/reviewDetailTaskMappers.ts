import type {
  ChangedFile as GeneratedChangedFile,
  ChunkedReview as GeneratedChunkedReview,
  FindingSeverityCounts as GeneratedFindingSeverityCounts,
  LlmStatus as GeneratedLlmStatus,
  MissingTest as GeneratedMissingTest,
  PrReviewSummary as GeneratedPrReviewSummary,
  PrRiskFile as GeneratedPrRiskFile,
  PrRiskProfile as GeneratedPrRiskProfile,
  RabbitMqStatus as GeneratedRabbitMqStatus,
  ReviewFinding as GeneratedReviewFinding,
  ReviewFindingTrace as GeneratedReviewFindingTrace,
  ReviewTaskStatus as GeneratedReviewTaskStatus,
  ReviewTaskSummary as GeneratedReviewTaskSummary,
  TimelineItem as GeneratedTimelineItem
} from "@/api/generated/reviewDetailTypes";
import type {
  AssessmentStatus,
  ChangedFileViewModel,
  ChunkedReviewViewModel,
  FindingFeedbackStatus,
  FindingSeverityCountsViewModel,
  LlmStatusViewModel,
  MissingTestViewModel,
  PrReviewSummaryViewModel,
  PrRiskFileViewModel,
  PrRiskProfileViewModel,
  RabbitMqStatusViewModel,
  ReviewFindingTraceViewModel,
  ReviewFindingViewModel,
  ReviewStatus,
  ReviewTaskDetail,
  RiskLevel,
  TimelineItemViewModel
} from "@/types";

const normalizeRiskLevel = (value: string | undefined, fallback: RiskLevel = "info") =>
  (value ?? fallback) as RiskLevel;

const normalizeReviewStatus = (value: string | undefined, fallback: ReviewStatus = "pending") =>
  (value ?? fallback) as ReviewStatus;

const normalizeTimelineStatus = (value: string | undefined): TimelineItemViewModel["status"] => {
  if (value === "done" || value === "completed") {
    return "done";
  }
  if (value === "current" || value === "running" || value === "in_progress") {
    return "current";
  }
  return "pending";
};

export const toReviewFindingTraceViewModel = (
  trace: GeneratedReviewFindingTrace | undefined
): ReviewFindingTraceViewModel | undefined => {
  if (!trace) {
    return undefined;
  }
  return {
    ...trace,
    detectorVersion: trace.detectorVersion ?? "",
    ruleConfigVersion: trace.ruleConfigVersion ?? 0,
    promptVersion: trace.promptVersion ?? "",
    contextVersion: trace.contextVersion ?? "",
    schemaVersion: trace.schemaVersion ?? "",
    verifierVersion: trace.verifierVersion ?? "",
    aggregationVersion: trace.aggregationVersion ?? "",
    policyVersion: trace.policyVersion ?? 0,
    originalSeverity: trace.originalSeverity ?? "",
    effectiveSeverity: trace.effectiveSeverity ?? "",
    originalConfidence: trace.originalConfidence ?? "",
    effectiveConfidence: trace.effectiveConfidence ?? "",
    downgradeReason: trace.downgradeReason ?? "",
    blockReason: trace.blockReason ?? "",
    anchorType: trace.anchorType ?? ""
  };
};

export const toReviewFindingViewModel = (
  finding: GeneratedReviewFinding
): ReviewFindingViewModel => {
  // Keep projection-only rows non-destructive; full detail rows are normalized below.
  if (Object.keys(finding).every((key) => key === "id")) {
    return finding as ReviewFindingViewModel;
  }
  return {
    ...finding,
    id: finding.id ?? 0,
    severity: normalizeRiskLevel(finding.severity),
    file: finding.file ?? "",
    line: finding.line ?? 0,
    message: finding.message ?? "",
    recommendation: finding.recommendation ?? "",
    trace: toReviewFindingTraceViewModel(finding.trace),
    feedbackStatus: (finding.feedbackStatus ?? "unreviewed") as FindingFeedbackStatus | string
  };
};

export const toMissingTestViewModel = (test: GeneratedMissingTest): MissingTestViewModel => ({
  ...test,
  file: test.file ?? "",
  method: test.method ?? "",
  type: test.type ?? "",
  suggestion: test.suggestion ?? ""
});

export const toChangedFileViewModel = (file: GeneratedChangedFile): ChangedFileViewModel => ({
  ...file,
  path: file.path ?? "",
  changeType: (file.changeType ?? "M") as ChangedFileViewModel["changeType"],
  additions: file.additions ?? 0,
  deletions: file.deletions ?? 0
});

export const toFindingSeverityCountsViewModel = (
  counts: GeneratedFindingSeverityCounts | undefined
): FindingSeverityCountsViewModel => ({
  ...counts,
  critical: counts?.critical ?? 0,
  high: counts?.high ?? 0,
  medium: counts?.medium ?? 0,
  low: counts?.low ?? 0,
  info: counts?.info ?? 0
});

export const toPrRiskFileViewModel = (file: GeneratedPrRiskFile): PrRiskFileViewModel => ({
  ...file,
  file: file.file ?? "",
  changeType: file.changeType ?? "M",
  additions: file.additions ?? 0,
  deletions: file.deletions ?? 0,
  findingCount: file.findingCount ?? 0,
  score: file.score ?? 0,
  reasons: file.reasons ?? []
});

export const toPrRiskProfileViewModel = (
  profile: GeneratedPrRiskProfile | undefined
): PrRiskProfileViewModel => ({
  ...profile,
  score: profile?.score ?? 0,
  level: normalizeRiskLevel(profile?.level),
  summary: profile?.summary ?? "暂无风险画像数据。",
  recommendHumanReview: Boolean(profile?.recommendHumanReview),
  humanReviewReason: profile?.humanReviewReason ?? "可按常规流程推进。",
  signals: profile?.signals ?? [],
  highRiskFiles: (profile?.highRiskFiles ?? []).map(toPrRiskFileViewModel)
});

export const toPrReviewSummaryViewModel = (
  summary: GeneratedPrReviewSummary | undefined,
  riskLevel?: string
): PrReviewSummaryViewModel => ({
  ...summary,
  overallRisk: summary?.overallRisk ?? riskLevel ?? "info",
  summary: summary?.summary ?? "暂无 PR 总评数据。",
  mergeRecommendation: summary?.mergeRecommendation ?? "可按团队流程继续复核。",
  recommendMerge: Boolean(summary?.recommendMerge),
  humanReviewRequired: Boolean(summary?.humanReviewRequired),
  keyRisks: summary?.keyRisks ?? [],
  focusFiles: summary?.focusFiles ?? [],
  githubCommentBody: summary?.githubCommentBody ?? ""
});

export const toTimelineItemViewModel = (item: GeneratedTimelineItem): TimelineItemViewModel => ({
  ...item,
  label: item.label ?? "",
  time: item.time ?? "",
  status: normalizeTimelineStatus(item.status)
});

export const toLlmStatusViewModel = (
  llm: GeneratedLlmStatus | undefined,
  fallbackStatus: ReviewStatus = "pending",
  fallbackRiskLevel: RiskLevel = "info",
  fallbackDuration = ""
): LlmStatusViewModel => ({
  ...llm,
  status: normalizeReviewStatus(llm?.status, fallbackStatus),
  duration: llm?.duration ?? fallbackDuration,
  riskLevel: normalizeRiskLevel(llm?.riskLevel, fallbackRiskLevel)
});

export const toChunkedReviewViewModel = (
  chunkedReview: GeneratedChunkedReview | undefined
): ChunkedReviewViewModel => ({
  ...chunkedReview,
  enabled: Boolean(chunkedReview?.enabled),
  chunkCount: chunkedReview?.chunkCount ?? 0,
  aggregateRisk: chunkedReview?.aggregateRisk ?? "info",
  aggregateFindings: chunkedReview?.aggregateFindings ?? 0,
  failedChunks: chunkedReview?.failedChunks ?? 0,
  reasons: chunkedReview?.reasons ?? []
});

export const toRabbitMqStatusViewModel = (
  rabbitMq: GeneratedRabbitMqStatus | undefined
): RabbitMqStatusViewModel => ({
  ...rabbitMq,
  deliveryCount: rabbitMq?.deliveryCount ?? 0,
  retryCount: rabbitMq?.retryCount ?? 0,
  consumeStatus: rabbitMq?.consumeStatus ?? "unknown"
});

export const normalizeReviewTaskDetail = (task: GeneratedReviewTaskSummary): ReviewTaskDetail => {
  const status = normalizeReviewStatus(task.status);
  const riskLevel = normalizeRiskLevel(task.riskLevel);
  const duration = task.duration ?? "";
  return {
    ...task,
    id: task.id ?? 0,
    prNumber: task.prNumber ?? 0,
    title: task.title ?? "",
    repository: task.repository ?? "",
    organization: task.organization ?? "",
    commit: task.commit ?? "",
    branch: task.branch ?? "",
    status,
    riskLevel,
    assessmentStatus: (task as GeneratedReviewTaskSummary & { assessmentStatus?: string }).assessmentStatus as
      | AssessmentStatus
      | string
      | undefined,
    mqRetries: task.mqRetries ?? 0,
    llmStatus: normalizeReviewStatus(task.llmStatus, status),
    source: task.source ?? "manual_input",
    triggerSource: task.triggerSource ?? "manual_input",
    createdAt: task.createdAt ?? "",
    duration,
    prUrl: task.prUrl ?? "",
    findings: (task.findings ?? []).map(toReviewFindingViewModel),
    missingTests: (task.missingTests ?? []).map(toMissingTestViewModel),
    changedFiles: (task.changedFiles ?? []).map(toChangedFileViewModel),
    findingTotal: task.findingTotal ?? task.findings?.length ?? 0,
    missingTestTotal: task.missingTestTotal ?? task.missingTests?.length ?? 0,
    changedFileTotal: task.changedFileTotal ?? task.changedFiles?.length ?? 0,
    findingSeverityCounts: toFindingSeverityCountsViewModel(task.findingSeverityCounts),
    timeline: (task.timeline ?? []).map(toTimelineItemViewModel),
    riskProfile: toPrRiskProfileViewModel(task.riskProfile),
    prSummary: toPrReviewSummaryViewModel(task.prSummary, task.riskProfile?.level),
    llm: toLlmStatusViewModel(task.llm, normalizeReviewStatus(task.llmStatus, status), riskLevel, duration),
    chunkedReview: toChunkedReviewViewModel(task.chunkedReview),
    rabbitMq: toRabbitMqStatusViewModel(task.rabbitMq),
    humanReviewRequired: Boolean(task.humanReviewRequired),
    humanReviewStatus: task.humanReviewStatus ?? "not_required"
  };
};

const mergeLatestTimeline = (
  timeline: TimelineItemViewModel[],
  latestTimeline?: GeneratedTimelineItem
): TimelineItemViewModel[] => {
  if (!latestTimeline) {
    return timeline;
  }
  const latest = toTimelineItemViewModel(latestTimeline);
  const normalizedTimeline = timeline.map((item) =>
    latest.status === "current" && item.status === "current" && item.label !== latest.label
      ? { ...item, status: "done" as TimelineItemViewModel["status"] }
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
  status: GeneratedReviewTaskStatus
): ReviewTaskDetail => {
  const normalizedStatus = normalizeReviewStatus(status.status, task.status);
  const normalizedLlmStatus = normalizeReviewStatus(status.llmStatus, task.llmStatus);
  const normalizedRiskLevel = normalizeRiskLevel(status.riskLevel, task.riskLevel);
  const duration = status.duration ?? task.duration;
  return {
    ...task,
    status: normalizedStatus,
    riskLevel: normalizedRiskLevel,
    assessmentStatus: status.assessmentStatus as AssessmentStatus | string | undefined,
    llmStatus: normalizedLlmStatus,
    duration,
    failureCategory: status.failureCategory,
    failureReason: status.failureReason,
    failureSuggestion: status.failureSuggestion,
    humanReviewRequired: status.humanReviewRequired ?? task.humanReviewRequired,
    humanReviewStatus: status.humanReviewStatus ?? task.humanReviewStatus,
    humanReviewNote: status.humanReviewNote,
    humanReviewBy: status.humanReviewBy,
    humanReviewedAt: status.humanReviewedAt,
    timeline: mergeLatestTimeline(task.timeline, status.latestTimeline),
    llm: toLlmStatusViewModel(
      { ...task.llm, status: normalizedLlmStatus, duration, riskLevel: normalizedRiskLevel },
      normalizedLlmStatus,
      normalizedRiskLevel,
      duration
    )
  };
};
