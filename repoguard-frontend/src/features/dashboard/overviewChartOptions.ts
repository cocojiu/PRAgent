import type { EChartsOption } from "echarts";
import type { ChartSlice, LlmQualityTrendPoint, ReviewTrendPoint } from "@/types";

const percentNumber = (value: string) => Number.parseFloat(value.replace("%", "")) || 0;

export const buildReviewTrendOption = (reviewTrend: ReviewTrendPoint[]): EChartsOption => ({
  grid: { left: 36, right: 18, top: 42, bottom: 32 },
  tooltip: { trigger: "axis" },
  xAxis: { type: "category", data: reviewTrend.map((item) => item.date), boundaryGap: false },
  yAxis: { type: "value", splitLine: { lineStyle: { color: "#e8eef6" } } },
  series: [
    {
      name: "审查数量",
      type: "line",
      smooth: true,
      data: reviewTrend.map((item) => item.value),
      symbolSize: 9,
      lineStyle: { color: "#1268ff", width: 3 },
      itemStyle: { color: "#1268ff" },
      areaStyle: { color: "rgba(18, 104, 255, 0.12)" },
      label: { show: true, position: "top", color: "#0f172a" }
    }
  ]
});

export const buildRiskDistributionOption = (riskDistribution: ChartSlice[]): EChartsOption => ({
  grid: { left: 38, right: 20, top: 36, bottom: 32 },
  tooltip: {},
  xAxis: { type: "category", data: riskDistribution.map((item) => item.name) },
  yAxis: { type: "value", splitLine: { lineStyle: { color: "#e8eef6" } } },
  series: [
    {
      type: "bar",
      data: riskDistribution.map((item) => ({ value: item.value, itemStyle: { color: item.color } })),
      barWidth: 36,
      label: { show: true, position: "top", color: "#0f172a" }
    }
  ]
});

export const buildLlmQualityTrendOption = (llmQualityTrend: LlmQualityTrendPoint[]): EChartsOption => ({
  grid: { left: 38, right: 18, top: 42, bottom: 32 },
  tooltip: { trigger: "axis" },
  xAxis: { type: "category", data: llmQualityTrend.map((item) => item.date), boundaryGap: false },
  yAxis: [
    { type: "value", name: "任务", splitLine: { lineStyle: { color: "#e8eef6" } } },
    { type: "value", name: "比例", min: 0, max: 100, axisLabel: { formatter: "{value}%" } }
  ],
  series: [
    {
      name: "任务数",
      type: "bar",
      data: llmQualityTrend.map((item) => item.taskCount),
      barWidth: 24,
      itemStyle: { color: "#2563eb" }
    },
    {
      name: "解析率",
      type: "line",
      yAxisIndex: 1,
      smooth: true,
      data: llmQualityTrend.map((item) => percentNumber(item.parseSuccessRate)),
      lineStyle: { color: "#22c55e", width: 3 },
      itemStyle: { color: "#22c55e" }
    },
    {
      name: "兜底率",
      type: "line",
      yAxisIndex: 1,
      smooth: true,
      data: llmQualityTrend.map((item) => percentNumber(item.fallbackRate)),
      lineStyle: { color: "#f59e0b", width: 3 },
      itemStyle: { color: "#f59e0b" }
    },
    {
      name: "部分补位率",
      type: "line",
      yAxisIndex: 1,
      smooth: true,
      data: llmQualityTrend.map((item) => percentNumber(item.partialFallbackRate)),
      lineStyle: { color: "#14b8a6", width: 3 },
      itemStyle: { color: "#14b8a6" }
    }
  ]
});
