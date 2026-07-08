import type { RiskLevel } from "./shared";

export type ReviewStatus =
  | "completed"
  | "reviewing"
  | "failed"
  | "queued"
  | "fallback"
  | "pending"
  | "pending_human_review"
  | "approved"
  | "changes_requested"
  | "rejected";
export type HumanReviewStatus = "not_required" | "pending" | "approved" | "changes_requested" | "rejected";
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

export interface ReviewTaskSummary extends ReviewTask {
  prUrl: string;
  /**
   * Summary responses keep large sections empty; use the paged review detail endpoints to load rows.
   */
  findings: ReviewFinding[];
  missingTests: MissingTest[];
  changedFiles: ChangedFile[];
  findingTotal: number;
  missingTestTotal: number;
  changedFileTotal: number;
  findingSeverityCounts?: FindingSeverityCounts;
  timeline: TimelineItem[];
  riskProfile: PrRiskProfile;
  prSummary: PrReviewSummary;
  llm: LlmStatus;
  chunkedReview: ChunkedReview;
  rabbitMq: RabbitMqStatus;
  archived?: boolean;
  archiveCleanupBatchId?: number;
  archiveBackupReference?: string;
  archivedAt?: string;
}

/**
 * Compatibility alias for detail page state. The root review endpoint returns a summary shell; heavy sections are
 * populated from paged endpoints after the first screen loads.
 */
export type ReviewTaskDetail = ReviewTaskSummary;

export interface ReviewTaskStatus {
  id: number;
  status: ReviewStatus;
  riskLevel: RiskLevel;
  llmStatus: ReviewStatus;
  duration: string;
  updatedAt?: string;
  failureCategory?: string;
  failureReason?: string;
  failureSuggestion?: string;
  latestTimeline?: TimelineItem;
  humanReviewRequired: boolean;
  humanReviewStatus: HumanReviewStatus | string;
  humanReviewNote?: string;
  humanReviewBy?: string;
  humanReviewedAt?: string;
}

export interface HumanReviewRequest {
  action: "approve" | "changes_requested" | "reject";
  note?: string;
}

export interface HumanReviewResponse {
  taskId: number;
  status: ReviewStatus | string;
  humanReviewRequired: boolean;
  humanReviewStatus: HumanReviewStatus | string;
  humanReviewNote?: string;
  humanReviewBy?: string;
  humanReviewedAt?: string;
  message: string;
}

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

export interface FindingFeedbackRequest {
  status: FindingFeedbackStatus;
  note?: string;
}

export interface FindingFeedbackResponse {
  findingId: number;
  taskId: number;
  feedbackStatus: FindingFeedbackStatus | string;
  feedbackNote?: string;
  feedbackBy?: string;
  feedbackAt?: string;
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

export interface ReviewFinding {
  id: number;
  severity: RiskLevel;
  file: string;
  line: number;
  message: string;
  recommendation: string;
  confidence?: "HIGH" | "MEDIUM" | "LOW" | string;
  evidence?: string;
  impact?: string;
  fixExample?: string;
  isBlocking?: boolean;
  reviewDimension?: string;
  feedbackStatus: FindingFeedbackStatus | string;
  feedbackNote?: string;
  feedbackBy?: string;
  feedbackAt?: string;
}

export interface MissingTest {
  file: string;
  method: string;
  type: string;
  suggestion: string;
}

export interface ChangedFile {
  path: string;
  changeType: "A" | "M" | "D" | "ADD" | "MODIFY" | "DELETE" | "RENAMED";
  additions: number;
  deletions: number;
}

export interface PrRiskProfile {
  score: number;
  level: RiskLevel;
  summary: string;
  recommendHumanReview: boolean;
  humanReviewReason: string;
  signals: string[];
  highRiskFiles: PrRiskFile[];
}

export interface FindingSeverityCounts {
  critical: number;
  high: number;
  medium: number;
  low: number;
  info: number;
}

export interface PrRiskFile {
  file: string;
  changeType: string;
  additions: number;
  deletions: number;
  findingCount: number;
  score: number;
  reasons: string[];
}

export interface PrReviewSummary {
  overallRisk: RiskLevel | string;
  summary: string;
  mergeRecommendation: string;
  recommendMerge: boolean;
  humanReviewRequired: boolean;
  keyRisks: string[];
  focusFiles: string[];
  githubCommentBody: string;
}

export interface TimelineItem {
  label: string;
  time: string;
  status: "done" | "current" | "pending";
}

export interface LlmStatus {
  status: ReviewStatus;
  duration: string;
  riskLevel: RiskLevel;
  provider?: string;
  model?: string;
  durationMs?: number;
  parseStatus?: string;
  fallbackReason?: string;
  promptSummary?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCost?: string;
}

export interface ChunkedReview {
  enabled: boolean;
  chunkCount: number;
  aggregateRisk?: RiskLevel | string;
  aggregateFindings: number;
  failedChunks: number;
  reasons: string[];
}

export interface RabbitMqStatus {
  deliveryCount: number;
  retryCount: number;
  consumeStatus: string;
}

export interface ReviewQuery {
  page: number;
  pageSize: number;
  repository?: string;
  status?: ReviewStatus | "";
  riskLevel?: RiskLevel | "";
  source?: ReviewTaskSource | "";
  triggerSource?: ReviewTaskTriggerSource | "";
  keyword?: string;
  cursorCreatedAt?: string;
  cursorId?: number;
}

export interface ManualReviewRequest {
  organization: string;
  repository: string;
  prNumber: number;
  title?: string;
  commit?: string;
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

export interface ReviewRetryResponse {
  taskId: number;
  status: ReviewStatus;
  message: string;
  retryCount: number;
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
