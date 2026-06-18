<template>
  <div v-loading="loading" class="overview-page">
    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="overviewMetricItems" :resolve-icon="getMetricIcon" />

    <section class="dashboard-grid">
      <article class="dashboard-card chart-card chart-card--wide">
        <h2>审查趋势</h2>
        <EChartPanel v-if="reviewTrend.length" :option="trendOption" />
        <el-empty v-else description="暂无审查趋势数据" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>风险分布</h2>
        <EChartPanel v-if="riskDistribution.length" :option="riskOption" />
        <el-empty v-else description="暂无风险分布数据" />
      </article>
      <article class="dashboard-card chart-card">
        <h2>规则命中</h2>
        <div v-if="ruleHits.length" class="donut-layout">
          <EChartPanel :option="ruleOption" />
          <ul class="rule-legend">
            <li v-for="rule in ruleHits" :key="rule.name">
              <span :style="{ background: rule.color }"></span>
              <b>{{ rule.name }}</b>
              <em>{{ rule.value }} ({{ rule.percent }})</em>
            </li>
          </ul>
        </div>
        <el-empty v-else description="暂无规则命中数据" />
      </article>
    </section>

    <LlmQualitySection
      :loading="loading"
      :trend-days="llmTrendDays"
      :trend-window-options="llmTrendWindowOptions"
      :quality-trend="llmQualityTrend"
      :quality-trend-option="llmQualityTrendOption"
      :quality-by-model="llmQualityByModel"
      :quality-by-repository="llmQualityByRepository"
      @trend-days-change="updateLlmTrendDays"
    />

    <DashboardBottomSection
      :high-risk-reviews="highRiskReviews"
      :failed-rules="failedRules"
      :system-health="systemHealth"
      :last-health-check-at="lastHealthCheckAt"
      :loading="loading"
      @refresh="loadOverview"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { Clock, FileText, ShieldAlert, Wallet } from "lucide-vue-next";
import DashboardBottomSection from "@/components/DashboardBottomSection.vue";
import EChartPanel from "@/components/EChartPanel.vue";
import LlmQualitySection from "@/components/LlmQualitySection.vue";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { fetchDashboardOverview } from "@/api/dashboard";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { getErrorMessage } from "@/utils/errors";
import type { DashboardOverview } from "@/types";
import {
  buildLlmQualityTrendOption,
  buildReviewTrendOption,
  buildRiskDistributionOption,
  buildRuleHitOption
} from "./overviewChartOptions";

const metricIconMap = {
  blue: FileText,
  red: ShieldAlert,
  green: Wallet,
  orange: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, FileText);
const loading = ref(false);
const errorMessage = ref("");
const lastHealthCheckAt = ref("-");
const llmTrendDays = ref(7);
const llmTrendWindowOptions = [
  { label: "7 天", value: 7 },
  { label: "30 天", value: 30 },
  { label: "90 天", value: 90 }
];
const overview = ref<DashboardOverview>({
  overviewMetrics: [],
  reviewTrend: [],
  riskDistribution: [],
  ruleHits: [],
  highRiskReviews: [],
  failedRules: [],
  systemHealth: [],
  llmQualityByModel: [],
  llmQualityByRepository: [],
  llmQualityTrend: []
});

const overviewMetrics = computed(() => overview.value.overviewMetrics);
const reviewTrend = computed(() => overview.value.reviewTrend);
const riskDistribution = computed(() => overview.value.riskDistribution);
const ruleHits = computed(() => overview.value.ruleHits);
const highRiskReviews = computed(() => overview.value.highRiskReviews);
const failedRules = computed(() => overview.value.failedRules);
const systemHealth = computed(() => overview.value.systemHealth);
const llmQualityByModel = computed(() => overview.value.llmQualityByModel ?? []);
const llmQualityByRepository = computed(() => overview.value.llmQualityByRepository ?? []);
const llmQualityTrend = computed(() => overview.value.llmQualityTrend ?? []);

const overviewMetricItems = computed<MetricGridItem[]>(() =>
  overviewMetrics.value.map((metric) => ({
    label: metric.label,
    value: metric.value,
    color: metric.color,
    note: `较上周${metric.trendType.includes("up") ? "上升" : "下降"} ${metric.trend}`,
    noteClass: metric.trendType === "up-danger" ? "trend danger" : "trend"
  }))
);

const totalRuleHits = computed(() => ruleHits.value.reduce((total, item) => total + item.value, 0));

const loadOverview = async () => {
  loading.value = true;
  errorMessage.value = "";
  try {
    overview.value = await fetchDashboardOverview(llmTrendDays.value);
    lastHealthCheckAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
  } catch (error) {
    errorMessage.value = getErrorMessage(error, "仪表盘数据加载失败");
    ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
  }
};

const updateLlmTrendDays = (days: number) => {
  llmTrendDays.value = days;
  void loadOverview();
};

onMounted(loadOverview);

const trendOption = computed(() => buildReviewTrendOption(reviewTrend.value));

const riskOption = computed(() => buildRiskDistributionOption(riskDistribution.value));

const ruleOption = computed(() => buildRuleHitOption(ruleHits.value, totalRuleHits.value));
const llmQualityTrendOption = computed(() => buildLlmQualityTrendOption(llmQualityTrend.value));
</script>
