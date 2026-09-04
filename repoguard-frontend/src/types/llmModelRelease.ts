export interface LlmModelReleaseRequest {
  releaseKey: string;
  provider: string;
  modelName: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  datasetId: string;
  datasetVersion: string;
  datasetFingerprint: string;
  trafficPercent: number;
  qualityGatePassed: boolean;
  precisionRate: number;
  recallRate: number;
  anchorRate: number;
  duplicateRate: number;
  parseFailureRate: number;
  p95LatencyMs: number;
  averageCost: number;
  totalTokens: number;
  blockers?: string[];
  evaluationReportId?: number;
}

export interface LlmModelRollbackRequest {
  reason: string;
}

export interface LlmModelRelease {
  id: number;
  releaseKey: string;
  provider: string;
  modelName: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  datasetId: string;
  datasetVersion: string;
  datasetFingerprint: string;
  state: "SHADOW" | "CANARY" | "ACTIVE" | "ROLLED_BACK" | string;
  trafficPercent: number;
  qualityGatePassed: boolean;
  precisionRate: number;
  recallRate: number;
  anchorRate: number;
  duplicateRate: number;
  parseFailureRate: number;
  p95LatencyMs: number;
  averageCost: number;
  totalTokens: number;
  blockers: string[];
  rollbackReason?: string;
  createdBy: string;
  createdAt?: string;
  updatedAt?: string;
  evaluationReportId?: number;
}

export interface LlmEvaluationObservationRequest {
  caseId: string;
  category: string;
  expectedFinding: boolean;
  expectedSeverity?: string;
  predictedFinding: boolean;
  predictedSeverity?: string;
  anchorValid: boolean;
  predictionKey?: string;
  parseSucceeded: boolean;
  latencyMs: number;
  totalTokens: number;
  estimatedCost: number;
  usefulComment?: boolean;
  commentPublishAttempted: boolean;
  commentPublished?: boolean;
  commentFixed?: boolean;
  commentIgnored?: boolean;
  ruleFindingCount: number;
  llmFindingCount: number;
  verifiedFindingCount: number;
  split: "FIXED_REGRESSION" | "ROLLING_OBSERVATION" | "UNSPECIFIED" | string;
  sourceRepositoryKey: string;
  language: string;
  changedFileCount: number;
  changedLineCount: number;
  fileTypeGroup: string;
  expectedLocationKey?: string;
}

export interface LlmEvaluationRequest {
  datasetId: string;
  datasetVersion: string;
  datasetKind: "REAL_PR" | "PROVISIONAL_REAL_PR" | "OFFLINE_SYNTHETIC" | string;
  sourceRepositoryCount: number;
  sampleCount: number;
  fixedRegressionSamples: number;
  rollingObservationSamples: number;
  authorized: boolean;
  anonymized: boolean;
  humanReviewed: boolean;
  sampleFingerprint: string;
  provider: string;
  model: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  chunkPolicyVersion: string;
  temperature: number;
  ruleVersion: string;
  codeRevision: string;
  /** Optional during the legacy report/request compatibility window. */
  verifierVersion?: string;
  aggregationVersion?: string;
  observations: LlmEvaluationObservationRequest[];
  minimumSamples?: number;
}

export interface LlmEvaluationRunRequest {
  runKey: string;
  dataDirectory: string;
  maxConcurrency: number;
  maxTokens: number;
  maxCost: number;
  maxDurationSeconds: number;
}

export interface LlmEvaluationRun {
  runId: string;
  runKey: string;
  status: "QUEUED" | "RUNNING" | "COMPLETE" | "FAILED" | "CANCELLED" | string;
  totalSamples: number;
  completedSamples: number;
  totalTokens: number;
  totalCost: number;
  reportId?: number;
  failureCode?: string;
  submittedAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface LlmEvaluationReportLifecycleRequest {
  action: "FREEZE" | "REVOKE_AUTHORIZATION" | "DELETE" | string;
  reason: string;
  secondApprover?: string;
  idempotencyKey: string;
}

export interface LlmEvaluationMetrics {
  labeledComments: number;
  usefulComments: number;
  falsePositiveComments: number;
  publishAttempts: number;
  publishedComments: number;
  fixedComments: number;
  ignoredComments: number;
  usefulCommentRate: number;
  falsePositiveCommentRate: number;
  publishSuccessRate: number;
  fixRate: number;
  ignoredRate: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  averageLatencyMs: number;
  averageTokensPerSample: number;
  averageCostPerSample: number;
  ruleFindings: number;
  llmFindings: number;
  verifiedFindings: number;
  ruleContributionRate: number;
  llmContributionRate: number;
  verifiedContributionRate: number;
}

export interface LlmEvaluationReport {
  id: number;
  reportKey: string;
  status: "COMPLETED" | "PROVISIONAL" | "FAILED" | "CANCELLED" | string;
  datasetId: string;
  datasetVersion: string;
  datasetKind: string;
  sourceRepositoryCount: number;
  sampleCount: number;
  fixedRegressionSamples: number;
  rollingObservationSamples: number;
  authorized: boolean;
  anonymized: boolean;
  humanReviewed: boolean;
  sampleFingerprint: string;
  provider: string;
  model: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  chunkPolicyVersion: string;
  temperature: number;
  ruleVersion: string;
  codeRevision: string;
  /** Legacy reports may not contain provenance fields until V91 is applied. */
  verifierVersion?: string;
  aggregationVersion?: string;
  expectedFindings: number;
  predictedFindings: number;
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  precision: number;
  recall: number;
  precisionWilsonLowerBound: number;
  anchorRate: number;
  duplicateRate: number;
  parseFailureRate: number;
  severityConfusion: Record<string, Record<string, number>>;
  totalLatencyMs: number;
  totalTokens: number;
  totalCost: number;
  blockers: string[];
  eligible: boolean;
  metrics: LlmEvaluationMetrics;
  createdBy: string;
  createdAt?: string;
  lifecycleStatus: "ACTIVE" | "EXPIRED" | "FROZEN" | "AUTHORIZATION_REVOKED" | "DELETED" | string;
  retentionDays: number;
  expiresAt?: string;
  authorizationRevokedAt?: string;
  frozenAt?: string;
  deletedAt?: string;
  lifecycleVersion: number;
}

export interface LlmEvaluationReportComparison {
  baselineReportId: number;
  candidateReportId: number;
  precisionDelta: number;
  recallDelta: number;
  anchorRateDelta: number;
  duplicateRateDelta: number;
  parseFailureRateDelta: number;
  p95LatencyDeltaMs: number;
  costDelta: number;
  candidateImproved: boolean;
  blockers: string[];
}

export interface LlmEvaluationExport {
  reportId: number;
  format: "json" | "html" | string;
  contentSha256: string;
  content: string;
}

export interface LlmModelBudget {
  month: string;
  tokenBudget: number;
  tokenUsed: number;
  tokenRemaining: number;
  costBudget: number;
  costUsed: number;
  costRemaining: number;
  exhausted: boolean;
}

export interface LlmModelReleaseCenter {
  configuredProvider: string;
  configuredModel: string;
  activeRelease?: LlmModelRelease;
  canaryRelease?: LlmModelRelease;
  releases: LlmModelRelease[];
  modelComparison: Array<{
    model: string;
    taskCount: number;
    averageDuration: string;
    averageTokens: string;
    averageCost: string;
    parseSuccessRate: string;
    fallbackRate: string;
    partialFallbackRate: string;
    validRate: string;
    falsePositiveRate: string;
  }>;
  monthlyBudget: LlmModelBudget;
  recommendedAction: string;
}

export interface LlmModelReleaseMetric {
  id: number;
  releaseId: number;
  releaseKey: string;
  provider: string;
  modelName: string;
  windowStart: string;
  windowEnd: string;
  sampleCount: number;
  totalTokens: number;
  totalCost: number;
  p95LatencyMs: number;
  parseFailureCount: number;
  fallbackCount: number;
  rollbackCount: number;
  alertState: string;
  alertCodes: string[];
  action: string;
  alertFingerprint: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface LlmModelReleaseAudit {
  id: number;
  releaseId: number;
  releaseKey: string;
  action: string;
  fromState?: string;
  toState: string;
  trafficPercent: number;
  operator: string;
  reason: string;
  detailsJson: string;
  eventHash: string;
  createdAt?: string;
  hashValid: boolean;
  hashStatus: string;
}

export interface LlmModelReleaseAuditVerification {
  auditId: number;
  releaseId: number;
  releaseKey: string;
  eventHash: string;
  calculatedHash: string;
  valid: boolean;
  status: string;
}

export interface LlmModelReleaseAuditExport {
  format: "json" | "csv" | string;
  recordCount: number;
  contentSha256: string;
  content: string;
}

export interface LlmModelReleaseDriftFinding {
  code: string;
  severity: string;
  resourceType: string;
  resourceId?: number;
  resourceKey?: string;
  observedValue: string;
  desiredValue: string;
  repairable: boolean;
}

export interface LlmModelReleaseDriftReleaseSummary {
  activeCount: number;
  canaryCount: number;
  activeReleaseIds: number[];
  canaryReleaseIds: number[];
  desiredActiveReleaseId?: number;
  desiredCanaryReleaseId?: number;
}

export interface LlmModelReleaseDriftAssignment {
  taskId: number;
  releaseKey: string;
  provider: string;
  model: string;
  taskStatus: string;
  started: boolean;
  issueCode: string;
  repairable: boolean;
}

export interface LlmModelReleaseDriftAssignmentSummary {
  assignedTaskCount: number;
  missingReleaseCount: number;
  metadataMismatchCount: number;
  runningTaskDriftCount: number;
  samples: LlmModelReleaseDriftAssignment[];
}

export interface LlmModelReleaseDrift {
  checkedAt: string;
  healthy: boolean;
  fingerprint: string;
  findings: LlmModelReleaseDriftFinding[];
  releaseSummary: LlmModelReleaseDriftReleaseSummary;
  assignmentSummary: LlmModelReleaseDriftAssignmentSummary;
}

export interface LlmModelReleaseDriftRepairRequest {
  idempotencyKey: string;
  previewFingerprint: string;
  confirm: boolean;
}

export interface LlmModelReleaseDriftRepair {
  operationKey: string;
  previewFingerprint: string;
  status: "PREVIEW" | "RUNNING" | "COMPLETED" | "FAILED" | string;
  changedReleaseCount: number;
  changedTaskCount: number;
  skippedRunningTaskCount: number;
  failureCode?: string;
  createdAt?: string;
  updatedAt?: string;
  preview?: LlmModelReleaseDrift;
  after?: LlmModelReleaseDrift;
}
