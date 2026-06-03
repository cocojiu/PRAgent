import type { RiskLevel } from "@/types";

const riskLabelMap: Record<RiskLevel, string> = {
  critical: "严重",
  high: "高风险",
  medium: "中风险",
  low: "低风险",
  info: "提示"
};

export const riskText = (risk: RiskLevel) => riskLabelMap[risk] ?? risk;
