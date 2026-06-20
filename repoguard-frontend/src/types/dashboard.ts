import type { MetricColor, RiskLevel } from "./shared";

export interface DashboardMetric {
  label: string;
  value: string;
  trend: string;
  trendType: "up" | "up-danger" | "down";
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

export interface LlmQualityByModel {
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
}

export interface LlmQualityByRepository {
  repository: string;
  taskCount: number;
  fallbackRate: string;
  partialFallbackRate: string;
  validRate: string;
  falsePositiveRate: string;
}

export interface LlmQualityTrendPoint {
  date: string;
  taskCount: number;
  parseSuccessRate: string;
  fallbackRate: string;
  partialFallbackRate: string;
}

export interface DashboardOverview {
  overviewMetrics: DashboardMetric[];
  reviewTrend: ReviewTrendPoint[];
  riskDistribution: ChartSlice[];
  ruleHits: Required<ChartSlice>[];
  highRiskReviews: HighRiskReview[];
  failedRules: FailedRuleStat[];
  systemHealth: SystemHealthItem[];
  llmQualityByModel: LlmQualityByModel[];
  llmQualityByRepository: LlmQualityByRepository[];
  llmQualityTrend: LlmQualityTrendPoint[];
}
