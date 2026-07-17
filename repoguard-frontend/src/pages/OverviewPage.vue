<template>
  <div v-loading="loading" class="overview-page">
    <el-alert v-if="errorMessage" class="page-alert" type="error" :title="errorMessage" show-icon :closable="false" />

    <MetricGrid :metrics="overviewMetricItems" :resolve-icon="getMetricIcon" />

    <DashboardChartSection
      :review-trend="reviewTrend"
      :risk-distribution="riskDistribution"
      :rule-hits="ruleHits"
      :trend-option="trendOption"
      :risk-option="riskOption"
      :rule-option="ruleOption"
    />

    <LlmQualitySection
      :loading="moduleLoading || llmQualityLoading"
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
      :loading="healthLoading"
      @refresh="loadSystemHealth"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from "vue";
import { Clock, FileText, ShieldAlert, Wallet } from "@lucide/vue";
import MetricGrid from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import {
  DashboardBottomSection,
  DashboardChartSection,
  LlmQualitySection,
  buildLlmQualityTrendOption,
  buildReviewTrendOption,
  buildRiskDistributionOption,
  buildRuleHitOption,
  useDashboardOverview
} from "@/features/dashboard";

const metricIconMap = {
  blue: FileText,
  red: ShieldAlert,
  green: Wallet,
  orange: Clock
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, FileText);
const {
  loading,
  moduleLoading,
  llmQualityLoading,
  healthLoading,
  errorMessage,
  lastHealthCheckAt,
  llmTrendDays,
  llmTrendWindowOptions,
  overviewMetricItems,
  reviewTrend,
  riskDistribution,
  ruleHits,
  totalRuleHits,
  highRiskReviews,
  failedRules,
  systemHealth,
  llmQualityByModel,
  llmQualityByRepository,
  llmQualityTrend,
  loadOverview,
  loadSystemHealth,
  updateLlmTrendDays
} = useDashboardOverview();

onMounted(loadOverview);

const trendOption = computed(() => buildReviewTrendOption(reviewTrend.value));

const riskOption = computed(() => buildRiskDistributionOption(riskDistribution.value));

const ruleOption = computed(() => buildRuleHitOption(ruleHits.value, totalRuleHits.value));
const llmQualityTrendOption = computed(() => buildLlmQualityTrendOption(llmQualityTrend.value));
</script>
