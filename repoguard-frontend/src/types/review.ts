import type { PageResponse, RiskLevel } from "./shared";
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
  ReviewTaskSummary as GeneratedReviewTaskSummary,
  TimelineItem as GeneratedTimelineItem
} from "@/api/generated/reviewDetailTypes";

export type ReviewStatus =
  | "completed"
  | "reviewing"
  | "failed"
  | "superseded"
  | "queued"
  | "fallback"
  | "pending"
  | "pending_human_review"
  | "approved"
  | "changes_requested"
  | "rejected";
export type HumanReviewStatus = "not_required" | "pending" | "approved" | "changes_requested" | "rejected";
export type AssessmentStatus = "complete" | "partial" | "failed" | "superseded";
export type FindingFeedbackStatus = "unreviewed" | "valid" | "false_positive" | "fixed" | "ignored";
export type ReviewTaskSource = "manual_input" | "github_pr_picker" | "github_webhook";
export type ReviewTaskTriggerSource = ReviewTaskSource | "existing_reused";

export interface ReviewTask {
  id: number;
  prNumber: number;
  title: string;
  repository: string;
  organization: string;
  commit: string;
  branch: string;
  status: ReviewStatus;
  riskLevel: RiskLevel;
  assessmentStatus?: AssessmentStatus | string;
  mqRetries: number;
  llmStatus: ReviewStatus;
  source: ReviewTaskSource | string;
  triggerSource: ReviewTaskTriggerSource | string;
  createdAt: string;
  duration: string;
  failureCategory?: string;
  failureReason?: string;
  failureSuggestion?: string;
  humanReviewRequired: boolean;
  humanReviewStatus: HumanReviewStatus | string;
  humanReviewNote?: string;
  humanReviewBy?: string;
  humanReviewedAt?: string;
}

/**
 * View model used by the review detail page. The API DTO is generated from OpenAPI; this type only adds
 * normalized defaults and UI-specific status unions after the explicit mapper runs.
 */
export type ReviewTaskDetailViewModel = Omit<
  GeneratedReviewTaskSummary,
  | "id"
  | "prNumber"
  | "title"
  | "repository"
  | "organization"
  | "commit"
  | "branch"
  | "status"
  | "riskLevel"
  | "mqRetries"
  | "llmStatus"
  | "source"
  | "triggerSource"
  | "createdAt"
  | "duration"
  | "prUrl"
  | "findings"
  | "missingTests"
  | "changedFiles"
  | "findingTotal"
  | "missingTestTotal"
  | "changedFileTotal"
  | "findingSeverityCounts"
  | "timeline"
  | "riskProfile"
  | "prSummary"
  | "llm"
  | "chunkedReview"
  | "rabbitMq"
  | "humanReviewRequired"
  | "humanReviewStatus"
> & {
  assessmentStatus?: AssessmentStatus | string;
  id: number;
  prNumber: number;
  title: string;
  repository: string;
  organization: string;
  commit: string;
  branch: string;
  status: ReviewStatus;
  riskLevel: RiskLevel;
  mqRetries: number;
  llmStatus: ReviewStatus;
  source: ReviewTaskSource | string;
  triggerSource: ReviewTaskTriggerSource | string;
  createdAt: string;
  duration: string;
  prUrl: string;
  findings: ReviewFindingViewModel[];
  missingTests: MissingTestViewModel[];
  changedFiles: ChangedFileViewModel[];
  findingTotal: number;
  missingTestTotal: number;
  changedFileTotal: number;
  findingSeverityCounts?: FindingSeverityCountsViewModel;
  timeline: TimelineItemViewModel[];
  riskProfile: PrRiskProfileViewModel;
  prSummary: PrReviewSummaryViewModel;
  llm: LlmStatusViewModel;
  chunkedReview: ChunkedReviewViewModel;
  rabbitMq: RabbitMqStatusViewModel;
  humanReviewRequired: boolean;
  humanReviewStatus: HumanReviewStatus | string;
  archived?: boolean;
  archiveCleanupBatchId?: number;
  archiveBackupReference?: string;
  archivedAt?: string;
};

export type ReviewTaskDetail = ReviewTaskDetailViewModel;

export interface GithubCommentPreview {
  taskId: number;
  prNumber: number;
  prUrl: string;
  writebackCheck: GithubCommentWritebackCheck;
  totalFindings: number;
  commentableCount: number;
  blockedCount: number;
  publishedCount: number;
  itemTotal: number;
  page: number;
  pageSize: number;
  commentableOnly: boolean;
  items: GithubCommentPreviewItem[];
}

export interface GithubCommentWritebackCheck {
  status:
    | "ready"
    | "repository_mismatch"
    | "repository_not_configured"
    | "token_missing"
    | "connection_failed"
    | string;
  level: "success" | "warning" | "danger" | string;
  taskOwner?: string;
  taskRepository?: string;
  configuredOwner?: string;
  configuredRepository?: string;
  repositoryMatched: boolean;
  tokenConfigured: boolean;
  connectionHealthy: boolean;
  lastError?: string;
  messages: string[];
}

export interface GithubCommentPreviewItem {
  findingId?: number | null;
  severity: RiskLevel;
  file: string;
  line?: number;
  message: string;
  recommendation: string;
  commentBody: string;
  commentable: boolean;
  targetType: "line" | "pull_request" | string;
  reason?: string;
  published?: boolean;
  publicationStatus?: string;
  publicationUrl?: string;
  publicationMessage?: string;
  publishedAt?: string;
  feedbackStatus?: FindingFeedbackStatus | string;
}

export interface GithubCommentPublish {
  taskId: number;
  batchId?: number | null;
  status?: "queued" | "running" | "completed" | "partial_failed" | "failed" | "skipped" | "empty" | string;
  totalFindings: number;
  attemptedCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
  nextRetryAt?: string;
  lastError?: string;
  items: GithubCommentPublishItem[];
}

export interface GithubCommentPublishItem {
  findingId?: number | null;
  file: string;
  line?: number;
  targetType: "line" | "pull_request" | string;
  success: boolean;
  status: "published" | "failed" | "skipped" | "already_published" | "downgraded_to_pr_comment" | string;
  message?: string;
  failureCategory?: string;
  failureReason?: string;
  failureSuggestion?: string;
  url?: string;
  githubCommentId?: number;
  publishedAt?: string;
}

export interface GithubCommentPublicationHistory {
  taskId: number;
  total: number;
  page: number;
  pageSize: number;
  status?: string;
  batches: GithubCommentPublicationBatch[];
}

export interface GithubCommentPublicationBatch {
  batchId: number;
  status: "queued" | "running" | "completed" | "partial_failed" | "failed" | "skipped" | "empty" | string;
  totalFindings: number;
  attemptedCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
  createdAt: string;
  completedAt?: string;
  nextRetryAt?: string;
  lastError?: string;
  items: GithubCommentPublicationHistoryItem[];
}

export type GithubCommentPublicationHistoryItem = GithubCommentPublishItem;

export type ReviewFindingTraceViewModel = Omit<
  GeneratedReviewFindingTrace,
  | "detectorVersion"
  | "ruleConfigVersion"
  | "promptVersion"
  | "contextVersion"
  | "schemaVersion"
  | "verifierVersion"
  | "aggregationVersion"
  | "policyVersion"
  | "originalSeverity"
  | "effectiveSeverity"
  | "originalConfidence"
  | "effectiveConfidence"
  | "downgradeReason"
  | "blockReason"
  | "anchorType"
> & {
  detectorVersion: string;
  ruleConfigVersion: number;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  verifierVersion: string;
  aggregationVersion: string;
  policyVersion: number;
  originalSeverity: string;
  effectiveSeverity: string;
  originalConfidence: string;
  effectiveConfidence: string;
  downgradeReason: string;
  blockReason: string;
  anchorType: string;
};

export type ReviewFindingViewModel = Omit<
  GeneratedReviewFinding,
  "id" | "severity" | "file" | "line" | "message" | "recommendation" | "trace" | "feedbackStatus"
> & {
  id: number;
  severity: RiskLevel;
  file: string;
  line: number;
  message: string;
  recommendation: string;
  trace?: ReviewFindingTraceViewModel;
  feedbackStatus: FindingFeedbackStatus | string;
};

export type MissingTestViewModel = Omit<GeneratedMissingTest, "file" | "method" | "type" | "suggestion"> & {
  file: string;
  method: string;
  type: string;
  suggestion: string;
};

export type ChangedFileViewModel = Omit<
  GeneratedChangedFile,
  "path" | "changeType" | "additions" | "deletions"
> & {
  path: string;
  changeType: "A" | "M" | "D" | "ADD" | "MODIFY" | "DELETE" | "RENAMED";
  additions: number;
  deletions: number;
};

export interface ReviewExecutionAttempt {
  id: number;
  taskId: number;
  attemptNo: number;
  generation: number;
  commitSha?: string;
  inputFingerprint?: string;
  workerId?: string;
  status: string;
  failureCategory?: string;
  budgetExhaustedStage?: string;
  policyVersion?: number;
  promptVersion?: string;
  contextVersion?: string;
  schemaVersion?: string;
  verifierVersion?: string;
  aggregationVersion?: string;
  diffFetchMs?: number;
  reviewMs?: number;
  persistMs?: number;
  totalMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCost?: number;
  queuedAt?: string;
  startedAt?: string;
  finishedAt?: string;
  payloadPurgedAt?: string;
  current: boolean;
}

export interface ReviewAttemptChangedFile {
  id: number;
  path: string;
  changeType: string;
  additions: number;
  deletions: number;
}

export interface ReviewAttemptFinding {
  id: number;
  category: string;
  severity: string;
  source?: string;
  ruleId?: string;
  file?: string;
  line?: number;
  message: string;
  recommendation?: string;
  confidence?: string;
  blocking?: boolean;
  feedbackStatus?: string;
  promptVersion?: string;
  contextVersion?: string;
  schemaVersion?: string;
  verifierVersion?: string;
  aggregationVersion?: string;
  findingFingerprint?: string;
  previousFindingId?: number;
  comparisonStatus?: "NEW" | "PERSISTING" | "RESOLVED" | "REGRESSED" | "UNMATCHED" | string;
  comparisonConfidence?: number;
  comparisonReason?: string;
  comparisonVersion?: string;
  comparisonAttemptId?: number;
}

export interface ReviewExecutionAttemptResult {
  attempt: ReviewExecutionAttempt;
  changedFiles: PageResponse<ReviewAttemptChangedFile>;
  findings: PageResponse<ReviewAttemptFinding>;
}

export interface ReviewFindingComparison {
  id: number;
  attemptId: number;
  baselineFindingId?: number;
  status: "NEW" | "PERSISTING" | "RESOLVED" | "REGRESSED" | "UNMATCHED" | string;
  findingFingerprint?: string;
  confidence: number;
  reason: string;
  comparisonVersion: string;
  category: string;
  severity: string;
  source?: string;
  ruleId?: string;
  file?: string;
  line?: number;
  message: string;
  recommendation?: string;
  blocking?: boolean;
  feedbackStatus?: FindingFeedbackStatus | string;
}

export interface ReviewFindingComparisonSummary {
  newCount: number;
  persistingCount: number;
  resolvedCount: number;
  regressedCount: number;
  unmatchedCount: number;
  total: number;
}

export interface ReviewAttemptComparison {
  taskId: number;
  baselineAttemptId?: number;
  candidateAttemptId: number;
  baselineCommitSha?: string;
  candidateCommitSha?: string;
  comparable: boolean;
  comparabilityReason: string;
  summary: ReviewFindingComparisonSummary;
  findings: PageResponse<ReviewFindingComparison>;
}

export type PrRiskProfileViewModel = Omit<
  GeneratedPrRiskProfile,
  "score" | "level" | "summary" | "recommendHumanReview" | "humanReviewReason" | "signals" | "highRiskFiles"
> & {
  score: number;
  level: RiskLevel;
  summary: string;
  recommendHumanReview: boolean;
  humanReviewReason: string;
  signals: string[];
  highRiskFiles: PrRiskFileViewModel[];
};

export type FindingSeverityCountsViewModel = Omit<
  GeneratedFindingSeverityCounts,
  "critical" | "high" | "medium" | "low" | "info"
> & {
  critical: number;
  high: number;
  medium: number;
  low: number;
  info: number;
};

export type PrRiskFileViewModel = Omit<
  GeneratedPrRiskFile,
  "file" | "changeType" | "additions" | "deletions" | "findingCount" | "score" | "reasons"
> & {
  file: string;
  changeType: string;
  additions: number;
  deletions: number;
  findingCount: number;
  score: number;
  reasons: string[];
};

export type PrReviewSummaryViewModel = Omit<
  GeneratedPrReviewSummary,
  | "overallRisk"
  | "summary"
  | "mergeRecommendation"
  | "recommendMerge"
  | "humanReviewRequired"
  | "keyRisks"
  | "focusFiles"
  | "githubCommentBody"
> & {
  overallRisk: RiskLevel | string;
  summary: string;
  mergeRecommendation: string;
  recommendMerge: boolean;
  humanReviewRequired: boolean;
  keyRisks: string[];
  focusFiles: string[];
  githubCommentBody: string;
};

export type TimelineItemViewModel = Omit<GeneratedTimelineItem, "label" | "time" | "status"> & {
  label: string;
  time: string;
  status: "done" | "current" | "pending";
};

export type LlmStatusViewModel = Omit<GeneratedLlmStatus, "status" | "duration" | "riskLevel"> & {
  status: ReviewStatus;
  duration: string;
  riskLevel: RiskLevel;
};

export type ChunkedReviewViewModel = Omit<
  GeneratedChunkedReview,
  "enabled" | "chunkCount" | "aggregateRisk" | "aggregateFindings" | "failedChunks" | "reasons"
> & {
  enabled: boolean;
  chunkCount: number;
  aggregateRisk?: RiskLevel | string;
  aggregateFindings: number;
  failedChunks: number;
  reasons: string[];
};

export type RabbitMqStatusViewModel = Omit<
  GeneratedRabbitMqStatus,
  "deliveryCount" | "retryCount" | "consumeStatus"
> & {
  deliveryCount: number;
  retryCount: number;
  consumeStatus: string;
};

export interface ReviewQuery {
  page: number;
  pageSize: number;
  repository?: string;
  status?: ReviewStatus | "";
  riskLevel?: RiskLevel | "";
  source?: ReviewTaskSource | "";
  triggerSource?: ReviewTaskTriggerSource | "";
  keyword?: string;
  cursor?: string;
}

export interface ReviewTaskListSummaryQuery {
  repository?: string;
  status?: ReviewStatus | "";
  riskLevel?: RiskLevel | "";
  source?: ReviewTaskSource | "";
  triggerSource?: ReviewTaskTriggerSource | "";
  keyword?: string;
}

export interface ReviewTaskListSummary {
  total: number;
  highRisk: number;
  failed: number;
  averageDurationSeconds: number;
}

export interface ManualReviewRequest {
  organization: string;
  repository: string;
  prNumber: number;
  title?: string;
  commit: string;
  branch?: string;
  source?: ReviewTaskSource | string;
}

export interface ManualReviewResponse {
  taskId: number;
  status: ReviewStatus;
  message: string;
  existing?: boolean;
  source?: string;
  triggerSource?: string;
}

export interface GithubPullRequestOptions {
  organization?: string;
  repository?: string;
  items: GithubPullRequestOption[];
}

export interface GithubPullRequestOption {
  number: number;
  title: string;
  branch?: string;
  commit?: string;
  headSha?: string;
  author?: string;
  url?: string;
  updatedAt?: string;
}
