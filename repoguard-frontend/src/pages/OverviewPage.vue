<template>
  <div class="overview-page">
    <MetricGrid :metrics="overviewMetricItems" :resolve-icon="getMetricIcon" />

    <section class="dashboard-grid">
      <article class="dashboard-card chart-card chart-card--wide">
        <h2>审查趋势</h2>
        <EChartPanel :option="trendOption" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>风险分布</h2>
        <EChartPanel :option="riskOption" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>规则命中</h2>
        <div class="donut-layout">
          <EChartPanel :option="ruleOption" />
          <ul class="rule-legend">
            <li v-for="rule in ruleHits" :key="rule.name">
              <span :style="{ background: rule.color }"></span>
              <b>{{ rule.name }}</b>
              <em>{{ rule.value }} ({{ rule.percent }})</em>
            </li>
          </ul>
        </div>
      </article>
    </section>

    <section class="bottom-grid">
      <article class="dashboard-card">
        <h2>近期高风险审查</h2>
        <el-table :data="highRiskReviews" class="rg-table" size="large" aria-label="近期高风险审查列表">
          <el-table-column prop="title" label="PR 标题" min-width="220" />
          <el-table-column prop="repository" label="仓库" width="150" />
          <el-table-column label="风险等级" width="110">
            <template #default="{ row }">
              <span :class="`risk-pill ${row.riskLevel}`">{{ riskText(row.riskLevel) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ruleHits" label="规则命中" width="100" />
          <el-table-column prop="reviewedAt" label="审查时间" width="170" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span class="status-pill success">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default>
              <RouterLink class="table-link" :to="{ name: 'tasks' }">查看</RouterLink>
            </template>
          </el-table-column>
        </el-table>
        <RouterLink class="card-footer-link" :to="{ name: 'tasks' }">查看更多</RouterLink>
      </article>

      <article class="dashboard-card">
        <h2>高频失败规则</h2>
        <el-table :data="failedRules" class="rg-table" size="large" aria-label="高频失败规则列表">
          <el-table-column prop="name" label="规则名称" min-width="180" />
          <el-table-column prop="count" label="命中次数" width="100" />
          <el-table-column label="趋势" width="100">
            <template #default="{ row }">
              <span :class="row.direction === 'up' ? 'trend danger' : 'trend'">
                {{ row.direction === "up" ? "上升" : "下降" }} {{ row.trend }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="percent" label="占比" width="80" />
        </el-table>
        <RouterLink class="card-footer-link" :to="{ name: 'rules' }">查看更多</RouterLink>
      </article>

      <article class="dashboard-card health-card">
        <h2>系统健康</h2>
        <div class="health-list">
          <div v-for="item in systemHealth" :key="item.name" class="health-item">
            <span>{{ item.name }}</span>
            <b>● {{ item.status }}</b>
          </div>
        </div>
        <div class="health-footer">
          <span>最后检查：{{ lastHealthCheckAt }}</span>
          <button class="table-link" type="button" @click="refreshHealth">刷新</button>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { ElMessage } from "element-plus";
import { Clock, FileText, ShieldAlert, Wallet } from "lucide-vue-next";
import type { EChartsOption } from "echarts";
import EChartPanel from "@/components/EChartPanel.vue";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { riskText } from "@/utils/risk";
import {
  failedRules,
  highRiskReviews,
  overviewMetrics,
  reviewTrend,
  riskDistribution,
  ruleHits,
  systemHealth
} from "@/mocks/dashboard";

const metricIconMap = {
  blue: FileText,
  red: ShieldAlert,
  green: Wallet,
  orange: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, FileText);
const lastHealthCheckAt = "2025-05-30 10:30:45";

const overviewMetricItems = computed<MetricGridItem[]>(() =>
  overviewMetrics.map((metric) => ({
    label: metric.label,
    value: metric.value,
    color: metric.color,
    note: `较上周${metric.trendType.includes("up") ? "上升" : "下降"} ${metric.trend}`,
    noteClass: metric.trendType === "up-danger" ? "trend danger" : "trend"
  }))
);

const totalRuleHits = computed(() => ruleHits.reduce((total, item) => total + item.value, 0));

const refreshHealth = () => {
  ElMessage.success("系统健康状态已刷新");
};

const trendOption = computed<EChartsOption>(() => ({
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
}));

const riskOption = computed<EChartsOption>(() => ({
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
}));

const ruleOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: "item" },
  series: [
    {
      type: "pie",
      radius: ["48%", "72%"],
      center: ["50%", "50%"],
      data: ruleHits.map((item) => ({ name: item.name, value: item.value, itemStyle: { color: item.color } })),
      label: { show: false },
      labelLine: { show: false }
    }
  ],
  graphic: {
    type: "text",
    left: "center",
    top: "center",
    style: { text: `总计\n${totalRuleHits.value}`, textAlign: "center", fill: "#0f172a", fontSize: 18, fontWeight: 700 }
  }
}));
</script>
