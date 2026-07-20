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

    <template v-if="deferredSectionsVisible">
      <LlmQualitySection
        :loading="deferredLoading || llmQualityLoading"
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
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Clock, FileText, ShieldAlert, Wallet } from "@lucide/vue";
import MetricGrid from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import DashboardChartSection from "@/features/dashboard/components/DashboardChartSection.vue";
import { useDashboardOverview } from "@/features/dashboard/composables/useDashboardOverview";
import {
  buildLlmQualityTrendOption,
  buildReviewTrendOption,
  buildRiskDistributionOption,
  buildRuleHitOption
} from "@/features/dashboard/overviewChartOptions";
import { recordRoutePerformanceMilestone } from "@/observability/frontendPerformanceDiagnosticsBridge";
import { routeNames } from "@/router/names";

const LlmQualitySection = defineAsyncComponent(
  () => import("@/features/dashboard/components/LlmQualitySection.vue")
);
const DashboardBottomSection = defineAsyncComponent(
  () => import("@/features/dashboard/components/DashboardBottomSection.vue")
);
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
  deferredLoading,
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
  loadDashboardModules,
  loadDeferredModules,
  loadSystemHealth,
  updateLlmTrendDays
} = useDashboardOverview();

onMounted(() => {
  void loadOverview({ deferModules: true });
});

const deferredSectionsVisible = ref(false);
let firstPaintFrame: number | undefined;
let secondPaintFrame: number | undefined;
let deferredIdleHandle: number | undefined;
let deferredTimer: ReturnType<typeof setTimeout> | undefined;

const loadPrimaryModulesAfterPaint = () => {
  firstPaintFrame = window.requestAnimationFrame(() => {
    firstPaintFrame = undefined;
    secondPaintFrame = window.requestAnimationFrame(() => {
      secondPaintFrame = undefined;
      void loadDashboardModules();
    });
  });
};

const activateDeferredSections = () => {
  deferredIdleHandle = undefined;
  deferredTimer = undefined;
  if (deferredSectionsVisible.value) {
    return;
  }
  deferredSectionsVisible.value = true;
  void loadDeferredModules();
  void loadSystemHealth();
};

const scheduleDeferredSections = () => {
  if (deferredSectionsVisible.value || deferredIdleHandle !== undefined || deferredTimer !== undefined) {
    return;
  }
  if ("requestIdleCallback" in window) {
    deferredIdleHandle = window.requestIdleCallback(activateDeferredSections, { timeout: 1200 });
    return;
  }
  deferredTimer = setTimeout(activateDeferredSections, 300);
};

watch(loading, async (isLoading, wasLoading) => {
  if (wasLoading && !isLoading && !errorMessage.value) {
    await nextTick();
    recordRoutePerformanceMilestone(routeNames.overview, "summary-ready");
    loadPrimaryModulesAfterPaint();
  }
});

watch(moduleLoading, async (isLoading, wasLoading) => {
  if (wasLoading && !isLoading) {
    await nextTick();
    if (!errorMessage.value) {
      recordRoutePerformanceMilestone(routeNames.overview, "data-ready");
    }
    scheduleDeferredSections();
  }
});

const trendOption = computed(() => buildReviewTrendOption(reviewTrend.value));

const riskOption = computed(() => buildRiskDistributionOption(riskDistribution.value));

const ruleOption = computed(() => buildRuleHitOption(ruleHits.value, totalRuleHits.value));
const llmQualityTrendOption = computed(() => buildLlmQualityTrendOption(llmQualityTrend.value));

onBeforeUnmount(() => {
  if (firstPaintFrame !== undefined) {
    window.cancelAnimationFrame(firstPaintFrame);
  }
  if (secondPaintFrame !== undefined) {
    window.cancelAnimationFrame(secondPaintFrame);
  }
  if (deferredIdleHandle !== undefined && "cancelIdleCallback" in window) {
    window.cancelIdleCallback(deferredIdleHandle);
  }
  if (deferredTimer !== undefined) {
    clearTimeout(deferredTimer);
  }
});
</script>
