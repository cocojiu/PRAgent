import type {
  ChartSlice,
  DashboardMetric,
  FailedRuleStat,
  HighRiskReview,
  ReviewTrendPoint,
  SystemHealthItem
} from "@/types";

export const overviewMetrics: DashboardMetric[] = [
  { label: "本周审查", value: "128", trend: "18.6%", trendType: "up", color: "blue" },
  { label: "高风险 PR", value: "23", trend: "35.3%", trendType: "up-danger", color: "red" },
  { label: "LLM 成本", value: "￥ 42.78", trend: "8.2%", trendType: "down", color: "green" },
  { label: "平均审查耗时", value: "2分 48秒", trend: "12.1%", trendType: "down", color: "orange" }
];

export const reviewTrend: ReviewTrendPoint[] = [
  { date: "05-24", value: 45 },
  { date: "05-25", value: 58 },
  { date: "05-26", value: 62 },
  { date: "05-27", value: 48 },
  { date: "05-28", value: 75 },
  { date: "05-29", value: 68 },
  { date: "05-30", value: 85 }
];

export const riskDistribution: ChartSlice[] = [
  { name: "高风险", value: 23, color: "#ef4444" },
  { name: "中风险", value: 52, color: "#f59e0b" },
  { name: "低风险", value: 41, color: "#2563eb" },
  { name: "提示", value: 12, color: "#22c55e" }
];

export const ruleHits: Required<ChartSlice>[] = [
  { name: "硬编码密钥检测", value: 48, percent: "20.3%", color: "#ef4444" },
  { name: "Controller 无测试", value: 60, percent: "25.4%", color: "#f59e0b" },
  { name: "Entity 无迁移", value: 36, percent: "15.3%", color: "#2563eb" },
  { name: "配置文件变更风险", value: 28, percent: "11.9%", color: "#22c55e" },
  { name: "TODO/FIXME/System.out", value: 40, percent: "16.9%", color: "#6366f1" },
  { name: "其他规则", value: 24, percent: "10.2%", color: "#14b8a6" }
];

export const highRiskReviews: HighRiskReview[] = [
  { title: "修复支付接口鉴权绕过漏洞", repository: "payment-service", riskLevel: "high", ruleHits: 8, reviewedAt: "2025-05-30 10:21", status: "已完成" },
  { title: "用户导出接口缺少权限校验", repository: "user-service", riskLevel: "high", ruleHits: 6, reviewedAt: "2025-05-30 09:47", status: "已完成" },
  { title: "订单金额计算逻辑优化", repository: "order-service", riskLevel: "high", ruleHits: 5, reviewedAt: "2025-05-29 16:33", status: "已完成" },
  { title: "短信验证码接口安全问题", repository: "auth-service", riskLevel: "high", ruleHits: 7, reviewedAt: "2025-05-29 15:02", status: "已完成" },
  { title: "导入订单缺少幂等校验", repository: "order-service", riskLevel: "high", ruleHits: 4, reviewedAt: "2025-05-29 11:18", status: "已完成" }
];

export const failedRules: FailedRuleStat[] = [
  { name: "Controller 无测试", count: 60, trend: "20.0%", direction: "up", percent: "25.4%" },
  { name: "硬编码密钥检测", count: 48, trend: "14.3%", direction: "up", percent: "20.3%" },
  { name: "TODO/FIXME/System.out", count: 40, trend: "11.1%", direction: "down", percent: "16.9%" },
  { name: "Entity 无迁移", count: 36, trend: "5.3%", direction: "down", percent: "15.3%" },
  { name: "配置文件变更风险", count: 28, trend: "3.4%", direction: "down", percent: "11.9%" }
];

export const systemHealth: SystemHealthItem[] = [
  { name: "MySQL", status: "正常" },
  { name: "RabbitMQ", status: "正常" },
  { name: "GitHub", status: "正常" },
  { name: "Spring AI Alibaba", status: "正常" }
];
