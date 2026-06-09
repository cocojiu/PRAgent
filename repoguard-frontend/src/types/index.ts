export type RiskLevel = "critical" | "high" | "medium" | "low" | "info";
export type ReviewStatus = "completed" | "reviewing" | "failed" | "queued" | "fallback" | "pending";
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
  url?: string;
  githubCommentId?: number;
  publishedAt?: string;
}

export interface GithubCommentPublicationHistory {
  taskId: number;
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
  severity: RiskLevel;
  file: string;
  line: number;
  message: string;
  recommendation: string;
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

export interface ReviewRuleConfig {
  id: string;
  name: string;
  scope: string;
  severity: RiskLevel;
  status: RuleStatus;
  hitCount: number;
  confidence: string;
  updatedAt: string;
  description: string;
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
}
