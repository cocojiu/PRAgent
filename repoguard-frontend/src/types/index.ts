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

