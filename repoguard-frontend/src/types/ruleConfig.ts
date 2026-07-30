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
  enforcementMode: EnforcementMode;
}

export interface ReviewRuleStatusRequest {
  status: RuleStatus;
}
