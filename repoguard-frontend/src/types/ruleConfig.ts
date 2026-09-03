import type { MetricColor, RiskLevel } from "./shared";

export type RuleStatus = "enabled" | "disabled";
export type EnforcementMode = "observe" | "comment" | "block";

export interface SimpleMetric {
  label: string;
  value: string;
  note: string;
  color: MetricColor;
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
  enforcementMode: EnforcementMode;
  detectorVersion: string;
  configVersion: number;
  policyVersion: number;
  qualityGate: ReviewRuleQualityGate;
  detectorType: "BUILTIN" | "REGEX" | "AST" | string;
  matcherExpression: string;
  exceptionPatterns: string;
}

export interface ReviewRulesResponse {
  metrics: SimpleMetric[];
  rules: ReviewRuleConfig[];
  qualityGroups: ReviewQualityGroup[];
  strategyPolicy: ReviewStrategyPolicy;
}

export interface ReviewRuleQualityGate {
  labeledSamples: number;
  labeledHighRiskSamples: number;
  precision: number;
  falsePositiveRate: number;
  anchorRate: number;
  duplicateRate: number;
  commentEligible: boolean;
  blockEligible: boolean;
  status: "INSUFFICIENT_SAMPLE" | "PASS" | "ALERT" | string;
  blockers: string[];
}

export interface ReviewQualityGroup {
  ruleId: string;
  source: string;
  repository: string;
  language: string;
  severity: string;
  versionKey: string;
  detectorVersion: string;
  ruleConfigVersion: number;
  policyVersion: number;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  verifierVersion: string;
  aggregationVersion: string;
  totalFindings: number;
  labeledCount: number;
  labeledCoverage: number;
  confirmedValidCount: number;
  falsePositiveCount: number;
  pendingCount: number;
  labeledPrecision: number;
  labeledFalsePositiveRate: number;
  highRiskCount: number;
  highRiskRate: number;
  blockingCount: number;
  blockingRate: number;
  revokedBlockingCount: number;
  anchoredCount: number;
  anchorRate: number;
  duplicateCount: number;
  duplicateRate: number;
  thresholdStatus: "INSUFFICIENT_SAMPLE" | "PASS" | "ALERT" | string;
  thresholdAlerts: string[];
}

export interface ReviewRulePolicyVersion {
  policyVersion: number;
  configVersion: number;
  detectorVersion: string;
  severity: RiskLevel;
  status: RuleStatus;
  confidence: string;
  enforcementMode: EnforcementMode;
  changeType: string;
  sourcePolicyVersion?: number | null;
  createdAt: string;
  active: boolean;
}

export interface ReviewStrategyPolicy {
  snapshotId: number;
  strategyVersion: number;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  verifierVersion: string;
  aggregationVersion: string;
  enforcementMode: EnforcementMode;
  replayVerified: boolean;
  active: boolean;
  changeType: string;
  sourceSnapshotId?: number | null;
  createdAt: string;
  qualityGate: ReviewRuleQualityGate;
}

export interface ReviewEnforcementModeRequest {
  enforcementMode: EnforcementMode;
  expectedSnapshotId: number;
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
  enforcementMode: EnforcementMode;
  detectorType?: "BUILTIN" | "REGEX" | "AST" | string;
  matcherExpression?: string;
  exceptionPatterns?: string;
}

export interface ReviewRuleStatusRequest {
  status: RuleStatus;
  expectedPolicyVersion: number;
}

export interface ReviewRuleRollbackRequest {
  expectedPolicyVersion: number;
}

export interface ReviewStrategyRollbackRequest {
  expectedSnapshotId: number;
}

export interface ReviewCalibrationVersion {
  ruleId: string;
  ruleName: string;
  detectorVersion: string;
  ruleConfigVersion: number;
  rulePolicyVersion: number;
  strategySnapshotId: number;
  strategyVersion: number;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  verifierVersion: string;
  aggregationVersion: string;
  ruleEnforcementMode: EnforcementMode;
  strategyEnforcementMode: EnforcementMode;
  replayVerified: boolean;
  versionKey: string;
}

export interface ReviewCalibrationSample {
  findingId: number;
  taskId: number;
  prNumber?: number | null;
  title: string;
  repository: string;
  organization: string;
  commitSha: string;
  prUrl: string;
  taskCreatedAt: string;
  source: string;
  ruleId: string;
  severity: string;
  confidence: string;
  filePath: string;
  lineNumber?: number | null;
  message: string;
  evidence: string;
  impact: string;
  recommendation: string;
  preconditions: string;
  issueType: string;
  verificationStatus: string;
  blockingCandidate: boolean;
  enforcementMode: EnforcementMode;
  feedbackStatus: string;
  versionKey: string;
}

export interface ReviewCalibrationQueue {
  version: ReviewCalibrationVersion;
  targetLabeledSamples: number;
  totalHighRiskFindings: number;
  labeledHighRiskSamples: number;
  confirmedValidSamples: number;
  falsePositiveSamples: number;
  pendingHighRiskSamples: number;
  remainingToTarget: number;
  qualityGate: ReviewRuleQualityGate;
  samples: ReviewCalibrationSample[];
}
