import { apiRequest } from "@/api/contracts";
import type { ApiRequestOptions } from "@/api/contracts";

/**
 * 查询仪表盘概览聚合数据。
 */
export const fetchDashboardOverview = (llmTrendDays = 7) =>
  apiRequest("fetchDashboardOverview", { llmTrendDays });

export const fetchDashboardSummary = (options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardSummary", undefined, options);

export const fetchDashboardReviewTrend = (options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardReviewTrend", undefined, options);

export const fetchDashboardRiskDistribution = (options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardRiskDistribution", undefined, options);

export const fetchDashboardRules = (options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardRules", undefined, options);

export const fetchDashboardHighRiskReviews = (options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardHighRiskReviews", undefined, options);

export const fetchDashboardLlmQuality = (llmTrendDays = 7, options?: ApiRequestOptions) =>
  apiRequest("fetchDashboardLlmQuality", { llmTrendDays }, options);

export const fetchSystemHealthSummary = (options?: ApiRequestOptions) =>
  apiRequest("fetchSystemHealthSummary", undefined, options);
