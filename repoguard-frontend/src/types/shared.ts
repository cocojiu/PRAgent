export type MetricColor = "blue" | "red" | "green" | "orange" | "purple";
export type RiskLevel = "critical" | "high" | "medium" | "low" | "info";

export interface PageResponse<T> {
  items: T[];
  total: number;
}
