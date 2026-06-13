export type RiskLevel = "critical" | "high" | "medium" | "low" | "info";
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
export type IntegrationStatus = "connected" | "missing_secret" | "failed";
export type RuleStatus = "enabled" | "disabled";
export type MetricColor = "blue" | "red" | "green" | "orange" | "purple";
export type ReviewTaskSource = "manual_input" | "github_pr_picker";
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

export interface ReviewTaskDetail extends ReviewTask {
  prUrl: string;
  findings: ReviewFinding[];
  missingTests: MissingTest[];
  changedFiles: ChangedFile[];
  timeline: TimelineItem[];
  llm: LlmStatus;
  rabbitMq: RabbitMqStatus;
}

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
  findingId: number;
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
  totalFindings: number;
  attemptedCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
  items: GithubCommentPublishItem[];
}

export interface GithubCommentPublishItem {
  findingId: number;
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
  status: "completed" | "partial_failed" | "failed" | "skipped" | "empty" | string;
  totalFindings: number;
  attemptedCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
  createdAt: string;
  completedAt?: string;
  items: GithubCommentPublicationHistoryItem[];
}

export interface GithubCommentPublicationHistoryItem extends GithubCommentPublishItem {}

export interface ReviewFinding {
  id: number;
  severity: RiskLevel;
  file: string;
  line: number;
  message: string;
  recommendation: string;
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
}

export interface RabbitMqStatus {
  deliveryCount: number;
  retryCount: number;
  consumeStatus: string;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
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

export interface DashboardMetric {
  label: string;
  value: string;
  trend: string;
  trendType: "up" | "up-danger" | "down";
  color: MetricColor;
}

export interface SimpleMetric {
  label: string;
  value: string;
  note: string;
  color: MetricColor;
}

export interface ReviewTrendPoint {
  date: string;
  value: number;
}

export interface ChartSlice {
  name: string;
  value: number;
  color: string;
  percent?: string;
}

export interface HighRiskReview {
  title: string;
  repository: string;
  riskLevel: RiskLevel;
  ruleHits: number;
  reviewedAt: string;
  status: string;
}

export interface FailedRuleStat {
  name: string;
  count: number;
  trend: string;
  direction: "up" | "down";
  percent: string;
}

export interface SystemHealthItem {
  name: string;
  status: string;
}

export interface DashboardOverview {
  overviewMetrics: DashboardMetric[];
  reviewTrend: ReviewTrendPoint[];
  riskDistribution: ChartSlice[];
  ruleHits: Required<ChartSlice>[];
  highRiskReviews: HighRiskReview[];
  failedRules: FailedRuleStat[];
  systemHealth: SystemHealthItem[];
}

export interface ActiveRabbitMqConfig {
  provider: string;
  status: string;
  runtimeConnectionStatus: string;
  baseUrl?: string;
  username?: string;
  virtualHost?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
  configVersion: string;
  switchNotice: string;
}

export interface RabbitMqTopology {
  exchange: string;
  queue: string;
  routingKey: string;
  deadLetterExchange: string;
  deadLetterQueue: string;
  deadLetterRoutingKey: string;
}

export interface MessageQueueMetric {
  label: string;
  value: string;
  note: string;
  noteClass?: string;
  color: MetricColor;
}

export interface RetryCompensationStatus {
  maxAttempts: number;
  intervalMs: number;
  batchSize: number;
  leaseMs: number;
  claimedTaskCount: number;
  latestSuccessAt?: string;
  latestFailureReason?: string;
}

export interface MessageQueueExceptionTask {
  taskId: number;
  organization?: string;
  repository?: string;
  prNumber?: number;
  status: "PUBLISH_FAILED" | "PUBLISH_CLAIMED" | "RETRY_EXHAUSTED" | "DLQ" | string;
  publishAttempts?: number;
  nextRetryAt?: string;
  claimedBy?: string;
  claimedAt?: string;
  lastError?: string;
}

export interface MessageQueueHealth {
  activeConfig: ActiveRabbitMqConfig;
  topology: RabbitMqTopology;
  metrics: MessageQueueMetric[];
  retryCompensation: RetryCompensationStatus;
  exceptionTasks: MessageQueueExceptionTask[];
  generatedAt: string;
  dataSource: string;
}

export interface MessageQueueRequeueResponse {
  taskId: number;
  status: "queued" | "publish_failed" | string;
  message: string;
  publishAttempts: number;
}

export interface ReviewRuleConfig {
  id: string;
  name: string;
  scope: string;
  applicableLanguages: string;
  filePatterns: string;
  severity: RiskLevel;
  status: RuleStatus;
  hitCount: number;
  confidence: string;
  updatedAt: string;
  description: string;
  positiveExample: string;
  falsePositiveGuidance: string;
}

export interface ReviewRulesResponse {
  metrics: SimpleMetric[];
  rules: ReviewRuleConfig[];
}

export interface ReviewRuleConfigRequest {
  id: string;
  name: string;
  scope: string;
  applicableLanguages: string;
  filePatterns: string;
  severity: RiskLevel;
  status: RuleStatus;
  confidence: number;
  description: string;
  positiveExample: string;
  falsePositiveGuidance: string;
}

export interface ReviewRuleStatusRequest {
  status: RuleStatus;
}

export interface BaseSettings {
  systemName: string;
  language: string;
  timezone: string;
  retentionDays: number;
}

export interface ReviewPolicySettings {
  maxDiffLines: number;
  llmTimeoutSeconds: number;
  workerConcurrency: number;
  autoComment: boolean;
  autoRetry: boolean;
}

export interface NotificationSettings {
  githubComment: boolean;
  highRiskPr: boolean;
  failedTask: boolean;
  email: string;
}

export interface SecuritySettings {
  webhookSignature: boolean;
  secretMasking: boolean;
  publicRepoAllowed: boolean;
  tokenTtlDays: number;
}

export interface SettingLog {
  time: string;
  operator: string;
  action: string;
  status: string;
}

export interface SystemSettings {
  base: BaseSettings;
  policy: ReviewPolicySettings;
  notification: NotificationSettings;
  security: SecuritySettings;
  logs: SettingLog[];
}

export type SystemSettingsRequest = Omit<SystemSettings, "logs">;

export interface IntegrationField {
  label: string;
  value: string;
  type: "text" | "password" | "select";
  placeholder?: string;
  options?: string[];
}

export interface IntegrationConfig {
  id: string;
  name: string;
  description: string;
  status: IntegrationStatus;
  statusText: string;
  message: string;
  metaLabel: string;
  metaValue: string;
  fields: IntegrationField[];
  diagnostics?: IntegrationDiagnosticItem[];
}

export interface IntegrationDiagnosticItem {
  label: string;
  value: string;
  status?: "success" | "warning" | "danger" | "info";
}

export interface GithubIntegrationConfig {
  provider: string;
  status: "configured" | "not_configured" | "failed";
  baseUrl: string;
  token?: string;
  defaultOwner?: string;
  defaultRepo?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
}

export interface GithubIntegrationConfigRequest {
  baseUrl: string;
  token?: string;
  defaultOwner?: string;
  defaultRepo?: string;
}

export interface ServiceIntegrationConfig {
  provider: string;
  status: "configured" | "not_configured" | "failed";
  baseUrl: string;
  username?: string;
  secret?: string;
  resource?: string;
  lastCheckedAt?: string;
  lastError?: string;
  updatedAt?: string;
}

export interface ServiceIntegrationConfigRequest {
  baseUrl: string;
  username?: string;
  secret?: string;
  resource?: string;
}

export interface ReviewPolicyConfig {
  llmEnabled: boolean;
  llmProvider: string;
  modelName: string;
  baseUrl?: string;
  apiKey?: string;
  timeoutSeconds: number;
  temperature: number;
  maxTokens: number;
  fallbackToRules: boolean;
  workerConcurrency: number;
  updatedAt?: string;
}

export type ReviewPolicyConfigRequest = ReviewPolicyConfig;

export interface ConnectionTestResult {
  success: boolean;
  status: "connected" | "failed";
  message: string;
  checkedAt: string;
  testedConfigSource?: "submitted_config" | "saved_config" | "runtime_config" | string;
  runtimeHealthy?: boolean | null;
  savedConfigHealthy?: boolean | null;
  mismatch?: boolean | null;
  runtimeConnectionStatus?: string | null;
  savedConfigStatus?: string | null;
}

export type NotificationLevel = "danger" | "warning" | "success" | "info";

export interface NotificationItem {
  id: string;
  level: NotificationLevel;
  title: string;
  description: string;
  time: string;
  targetPath?: string;
  createdAt?: string;
}

export interface NotificationCenter {
  total: number;
  generatedAt: string;
  items: NotificationItem[];
}
