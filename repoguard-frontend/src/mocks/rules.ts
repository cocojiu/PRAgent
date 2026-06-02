import type { RiskLevel } from "@/types";

export type RuleStatus = "enabled" | "disabled";

export interface ReviewRuleConfig {
  id: string;
  name: string;
  scope: string;
  severity: RiskLevel;
  status: RuleStatus;
  hitCount: number;
  confidence: string;
  updatedAt: string;
  description: string;
}

export const ruleMetrics = [
  { label: "启用规则", value: "8", note: "覆盖 Java PR 审查", color: "blue" },
  { label: "高风险规则", value: "3", note: "阻断级质量护栏", color: "red" },
  { label: "今日命中", value: "236", note: "较昨日 ↑ 12.4%", color: "orange" },
  { label: "平均置信度", value: "92%", note: "规则结果稳定", color: "green" }
];

export const reviewRules: ReviewRuleConfig[] = [
  {
    id: "RG-SECRET-001",
    name: "硬编码密钥检测",
    scope: "Java / YAML / Properties",
    severity: "high",
    status: "enabled",
    hitCount: 48,
    confidence: "96%",
    updatedAt: "2025-05-30 11:20",
    description: "检测 password、secret、accessKey、token 等疑似敏感信息。"
  },
  {
    id: "RG-API-001",
    name: "Controller 无测试",
    scope: "Controller / REST API",
    severity: "medium",
    status: "enabled",
    hitCount: 60,
    confidence: "88%",
    updatedAt: "2025-05-30 10:42",
    description: "Controller 改动但 PR 未包含测试文件时提示测试缺口。"
  },
  {
    id: "RG-DB-001",
    name: "Entity 无迁移",
    scope: "Entity / Model / SQL",
    severity: "high",
    status: "enabled",
    hitCount: 36,
    confidence: "93%",
    updatedAt: "2025-05-29 18:05",
    description: "Entity 字段改动但无 migration SQL 或 changelog 时标记高风险。"
  },
  {
    id: "RG-CONFIG-001",
    name: "配置文件变更风险",
    scope: "application.yml / bootstrap.yml",
    severity: "high",
    status: "enabled",
    hitCount: 28,
    confidence: "91%",
    updatedAt: "2025-05-29 16:18",
    description: "生产配置、启动配置和基础设施配置变更默认标记为高风险。"
  },
  {
    id: "RG-CLEAN-001",
    name: "TODO/FIXME/System.out",
    scope: "Java Patch",
    severity: "low",
    status: "enabled",
    hitCount: 40,
    confidence: "97%",
    updatedAt: "2025-05-28 14:55",
    description: "识别调试残留、临时代码和不规范标准输出。"
  },
  {
    id: "RG-LOG-001",
    name: "异常日志规范",
    scope: "try/catch",
    severity: "medium",
    status: "disabled",
    hitCount: 12,
    confidence: "84%",
    updatedAt: "2025-05-26 09:30",
    description: "检测吞异常、仅 printStackTrace 或缺少上下文日志的问题。"
  }
];

