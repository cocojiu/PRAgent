<template>
  <section class="report-metrics" aria-label="评估报告聚合指标">
    <h3>报告 #{{ report.id }} · 聚合指标</h3>
    <p>以下统计仅反映本次数据集，不代表线上人工反馈或代表性性能。</p>
    <dl class="report-metrics-grid">
      <div><dt>正确命中 / 误报 / 漏报</dt><dd>{{ count(report.truePositives) }} / {{ count(report.falsePositives) }} / {{ count(report.falseNegatives) }}</dd></div>
      <div><dt>发现贡献（LLM / 规则 / 复核）</dt><dd>{{ count(report.metrics?.llmFindings) }} / {{ count(report.metrics?.ruleFindings) }} / {{ count(report.metrics?.verifiedFindings) }}</dd></div>
      <div><dt>解析失败样本</dt><dd>{{ count(report.metrics?.parseFailures) }}</dd></div>
      <div><dt>传输失败样本 / 比例</dt><dd>{{ count(report.metrics?.transportFailures) }} / {{ rate(report.metrics?.transportFailureRate) }}</dd></div>
      <div><dt>样本耗时 P50 / P95</dt><dd>{{ duration(report.metrics?.p50LatencyMs) }} / {{ duration(report.metrics?.p95LatencyMs) }}</dd></div>
      <div><dt>累计 token / 估算费用</dt><dd>{{ count(report.totalTokens) }} / {{ estimatedCostText(report.totalCost) }}</dd></div>
      <div><dt>评论标注 / 发布尝试 / 发布成功</dt><dd>{{ count(report.metrics?.labeledComments) }} / {{ count(report.metrics?.publishAttempts) }} / {{ count(report.metrics?.publishedComments) }}</dd></div>
    </dl>
    <p>较早报告未记录的指标显示“未记录”；费用为系统估算，非实际账单。</p>
  </section>
</template>

<script setup lang="ts">
import type { LlmEvaluationReport } from "@/types";
import { estimatedCostText } from "@/utils/estimatedCost";

defineProps<{ report: LlmEvaluationReport }>();

const finiteNonNegative = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0;
const count = (value: unknown) => finiteNonNegative(value) && Number.isSafeInteger(value)
  ? String(value) : "未记录";
const rate = (value: unknown) => finiteNonNegative(value) && value <= 1
  ? `${(value * 100).toFixed(1)}%` : "未记录";
const duration = (value: unknown) => finiteNonNegative(value)
  ? `${(value / 1000).toFixed(2)} 秒` : "未记录";
</script>

<style scoped>
.report-metrics { margin-top: 16px; }
.report-metrics p { color: var(--el-text-color-secondary); }
.report-metrics-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.report-metrics-grid > div { min-width: 0; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.report-metrics-grid dt { color: var(--el-text-color-secondary); }
.report-metrics-grid dd { margin: 8px 0 0; overflow-wrap: anywhere; }
@media (max-width: 600px) { .report-metrics-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
