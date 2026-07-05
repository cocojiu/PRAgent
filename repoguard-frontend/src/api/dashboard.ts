import { apiRequest } from "@/api/contracts";

/**
 * 查询仪表盘概览聚合数据。
 */
export const fetchDashboardOverview = (llmTrendDays = 7) =>
  apiRequest("fetchDashboardOverview", { llmTrendDays });

export const fetchDashboardSummary = () =>
  apiRequest("fetchDashboardSummary", undefined);

export const fetchDashboardReviewTrend = () =>
  apiRequest("fetchDashboardReviewTrend", undefined);

export const fetchDashboardRiskDistribution = () =>
  apiRequest("fetchDashboardRiskDistribution", undefined);

export const fetchDashboardRules = () =>
  apiRequest("fetchDashboardRules", undefined);

export const fetchDashboardHighRiskReviews = () =>
  apiRequest("fetchDashboardHighRiskReviews", undefined);

export const fetchDashboardLlmQuality = (llmTrendDays = 7) =>
  apiRequest("fetchDashboardLlmQuality", { llmTrendDays });

export const fetchSystemHealthSummary = () =>
  apiRequest("fetchSystemHealthSummary", undefined);
