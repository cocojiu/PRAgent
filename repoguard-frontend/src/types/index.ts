export type RiskLevel = "critical" | "high" | "medium" | "low" | "info";
export type ReviewStatus = "completed" | "reviewing" | "failed" | "queued";
export type IntegrationStatus = "connected" | "missing_secret" | "failed";

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
  createdAt: string;
  duration: string;
}

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
  changeType: "A" | "M" | "D";
  additions: number;
  deletions: number;
}

export interface TimelineItem {
  label: string;
  time: string;
  status: "done" | "current";
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
