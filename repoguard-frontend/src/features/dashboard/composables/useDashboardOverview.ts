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
  let overviewRequestSeq = 0;
  let moduleRequestSeq = 0;
  let deferredRequestSeq = 0;
  let llmQualityRequestSeq = 0;
  let healthRequestSeq = 0;

  const loading = ref(false);
  const moduleLoading = ref(false);
  const deferredLoading = ref(false);
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

  const loadOverview = async (options: { deferModules?: boolean } = {}) => {
    const requestSeq = ++overviewRequestSeq;
    loading.value = true;
    errorMessage.value = "";
    try {
      const overviewMetrics = await fetchDashboardSummary();
      if (requestSeq !== overviewRequestSeq) {
        return;
      }
      overview.value = {
        ...overview.value,
        overviewMetrics
      };
      if (!options.deferModules) {
        void loadDashboardModules(requestSeq);
        void loadDeferredModules(requestSeq);
        void loadSystemHealth(requestSeq);
      }
    } catch (error) {
      if (requestSeq !== overviewRequestSeq) {
        return;
      }
      errorMessage.value = getErrorMessage(error, "仪表盘数据加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      if (requestSeq === overviewRequestSeq) {
        loading.value = false;
      }
    }
  };

  const loadDashboardModules = async (overviewSeq = overviewRequestSeq) => {
    const requestSeq = ++moduleRequestSeq;
    moduleLoading.value = true;
    try {
      const [reviewTrend, riskDistribution, rules] = await Promise.all([
        fetchDashboardReviewTrend(),
        fetchDashboardRiskDistribution(),
        fetchDashboardRules()
      ]);
      if (requestSeq !== moduleRequestSeq || overviewSeq !== overviewRequestSeq) {
        return;
      }
      overview.value = {
        ...overview.value,
        reviewTrend,
        riskDistribution,
        ruleHits: rules.ruleHits,
        failedRules: rules.failedRules
      };
    } catch (error) {
      if (requestSeq !== moduleRequestSeq || overviewSeq !== overviewRequestSeq) {
        return;
      }
      errorMessage.value = getErrorMessage(error, "仪表盘模块数据加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      if (requestSeq === moduleRequestSeq) {
        moduleLoading.value = false;
      }
    }
  };

  const loadDeferredModules = async (overviewSeq = overviewRequestSeq) => {
    const requestSeq = ++deferredRequestSeq;
    deferredLoading.value = true;
    const requestedLlmTrendDays = llmTrendDays.value;
    try {
      const [highRiskReviews, llmQuality] = await Promise.all([
        fetchDashboardHighRiskReviews(),
        fetchDashboardLlmQuality(requestedLlmTrendDays)
      ]);
      if (
        requestSeq !== deferredRequestSeq
        || overviewSeq !== overviewRequestSeq
        || llmTrendDays.value !== requestedLlmTrendDays
      ) {
        return;
      }
      overview.value = {
        ...overview.value,
        highRiskReviews,
        llmQualityByModel: llmQuality.byModel,
        llmQualityByRepository: llmQuality.byRepository,
        llmQualityTrend: llmQuality.trend
      };
    } catch (error) {
      if (requestSeq !== deferredRequestSeq || overviewSeq !== overviewRequestSeq) {
        return;
      }
      ElMessage.error(getErrorMessage(error, "仪表盘扩展数据加载失败"));
    } finally {
      if (requestSeq === deferredRequestSeq) {
        deferredLoading.value = false;
      }
    }
  };

  const loadLlmQuality = async () => {
    const requestSeq = ++llmQualityRequestSeq;
    llmQualityLoading.value = true;
    const requestedLlmTrendDays = llmTrendDays.value;
    try {
      const llmQuality = await fetchDashboardLlmQuality(requestedLlmTrendDays);
      if (requestSeq !== llmQualityRequestSeq || llmTrendDays.value !== requestedLlmTrendDays) {
        return;
      }
      overview.value = {
        ...overview.value,
        llmQualityByModel: llmQuality.byModel,
        llmQualityByRepository: llmQuality.byRepository,
        llmQualityTrend: llmQuality.trend
      };
    } catch (error) {
      if (requestSeq !== llmQualityRequestSeq) {
        return;
      }
      ElMessage.error(getErrorMessage(error, "LLM 质量数据加载失败"));
    } finally {
      if (requestSeq === llmQualityRequestSeq) {
        llmQualityLoading.value = false;
      }
    }
  };

  const loadSystemHealth = async (overviewSeq = overviewRequestSeq) => {
    const requestSeq = ++healthRequestSeq;
    healthLoading.value = true;
    try {
      const systemHealth = await fetchSystemHealthSummary();
      if (requestSeq !== healthRequestSeq || overviewSeq !== overviewRequestSeq) {
        return;
      }
      overview.value = {
        ...overview.value,
        systemHealth
      };
      lastHealthCheckAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
    } catch (error) {
      if (requestSeq !== healthRequestSeq || overviewSeq !== overviewRequestSeq) {
        return;
      }
      ElMessage.warning(getErrorMessage(error, "系统健康检查加载失败"));
    } finally {
      if (requestSeq === healthRequestSeq) {
        healthLoading.value = false;
      }
    }
  };

  const updateLlmTrendDays = (days: number) => {
    llmTrendDays.value = days;
    void loadLlmQuality();
  };

  return {
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
    loadDeferredModules,
    loadLlmQuality,
    loadSystemHealth,
    updateLlmTrendDays
  };
};
