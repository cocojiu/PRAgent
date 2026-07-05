import { computed, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchDashboardHighRiskReviews,
  fetchDashboardLlmQuality,
  fetchDashboardReviewTrend,
  fetchDashboardRiskDistribution,
  fetchDashboardRules,
  fetchDashboardSummary,
  fetchSystemHealthSummary
} from "@/api/dashboard";
import type { MetricGridItem } from "@/components/MetricGrid.vue";
import { getErrorMessage } from "@/utils/errors";
import type { DashboardOverview } from "@/types";

const createEmptyOverview = (): DashboardOverview => ({
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

export const llmTrendWindowOptions = [
  { label: "7 天", value: 7 },
  { label: "30 天", value: 30 },
  { label: "90 天", value: 90 }
];

export const useDashboardOverview = () => {
  const loading = ref(false);
  const moduleLoading = ref(false);
  const llmQualityLoading = ref(false);
  const healthLoading = ref(false);
  const errorMessage = ref("");
  const lastHealthCheckAt = ref("-");
  const llmTrendDays = ref(7);
  const overview = ref<DashboardOverview>(createEmptyOverview());

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
      overview.value = {
        ...overview.value,
        overviewMetrics: await fetchDashboardSummary()
      };
      void loadDashboardModules();
      void loadSystemHealth();
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "仪表盘数据加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      loading.value = false;
    }
  };

  const loadDashboardModules = async () => {
    moduleLoading.value = true;
    const requestedLlmTrendDays = llmTrendDays.value;
    try {
      const [reviewTrend, riskDistribution, rules, highRiskReviews, llmQuality] = await Promise.all([
        fetchDashboardReviewTrend(),
        fetchDashboardRiskDistribution(),
        fetchDashboardRules(),
        fetchDashboardHighRiskReviews(),
        fetchDashboardLlmQuality(requestedLlmTrendDays)
      ]);
      const nextOverview = {
        ...overview.value,
        reviewTrend,
        riskDistribution,
        ruleHits: rules.ruleHits,
        failedRules: rules.failedRules,
        highRiskReviews
      };
      if (llmTrendDays.value !== requestedLlmTrendDays) {
        overview.value = nextOverview;
        return;
      }
      overview.value = {
        ...nextOverview,
        llmQualityByModel: llmQuality.byModel,
        llmQualityByRepository: llmQuality.byRepository,
        llmQualityTrend: llmQuality.trend
      };
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "仪表盘模块数据加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      moduleLoading.value = false;
    }
  };

  const loadLlmQuality = async () => {
    llmQualityLoading.value = true;
    try {
      const llmQuality = await fetchDashboardLlmQuality(llmTrendDays.value);
      overview.value = {
        ...overview.value,
        llmQualityByModel: llmQuality.byModel,
        llmQualityByRepository: llmQuality.byRepository,
        llmQualityTrend: llmQuality.trend
      };
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "LLM 质量数据加载失败"));
    } finally {
      llmQualityLoading.value = false;
    }
  };

  const loadSystemHealth = async () => {
    healthLoading.value = true;
    try {
      const systemHealth = await fetchSystemHealthSummary();
      overview.value = {
        ...overview.value,
        systemHealth
      };
      lastHealthCheckAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
    } catch (error) {
      ElMessage.warning(getErrorMessage(error, "系统健康检查加载失败"));
    } finally {
      healthLoading.value = false;
    }
  };

  const updateLlmTrendDays = (days: number) => {
    llmTrendDays.value = days;
    void loadLlmQuality();
  };

  return {
    loading,
    moduleLoading,
    llmQualityLoading,
    healthLoading,
    errorMessage,
    lastHealthCheckAt,
    llmTrendDays,
    llmTrendWindowOptions,
    overviewMetricItems,
    overviewMetrics,
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
    loadLlmQuality,
    loadSystemHealth,
    updateLlmTrendDays
  };
};
