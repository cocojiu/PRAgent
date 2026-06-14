import { request } from "@/api/client";
import type { DashboardOverview } from "@/types";

/**
 * 查询仪表盘概览聚合数据。
 */
export const fetchDashboardOverview = (llmTrendDays = 7) =>
  request<DashboardOverview>(`/api/v1/dashboard/overview?llmTrendDays=${encodeURIComponent(llmTrendDays)}`);
