import { computed, getCurrentScope, onScopeDispose, reactive, ref } from "vue";
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
import type { ApiRequestOptions } from "@/api/contracts";
import type { MetricGridItem } from "@/components/MetricGrid.vue";
import { getErrorMessage } from "@/utils/errors";
import { formatDateTime } from "@/utils/dateTime";
import type { DashboardOverview } from "@/types";

const moduleKeys = ["summary", "reviewTrend", "riskDistribution", "rules", "highRiskReviews", "llmQuality", "systemHealth"] as const;
type ModuleKey = typeof moduleKeys[number];
export type DashboardModuleState = {
  loading: boolean;
  error: string;
  lastSuccessAt: string;
  lastSuccessLabel: string;
};
export type DashboardModuleStates = Record<ModuleKey, DashboardModuleState>;

const createEmptyOverview = (): DashboardOverview => ({
  overviewMetrics: [], reviewTrend: [], riskDistribution: [], ruleHits: [], highRiskReviews: [],
  failedRules: [], systemHealth: [], llmQualityByModel: [], llmQualityByRepository: [], llmQualityTrend: []
});

export const llmTrendWindowOptions = [
  { label: "7 天", value: 7 },
  { label: "30 天", value: 30 },
  { label: "90 天", value: 90 }
];

export const useDashboardOverview = () => {
  let overviewRequestSeq = 0;
  let disposed = false;
  const controllers: Partial<Record<ModuleKey, AbortController>> = {};
  const moduleStates = reactive<DashboardModuleStates>(Object.fromEntries(moduleKeys.map(key => [key, {
    loading: false, error: "", lastSuccessAt: "", lastSuccessLabel: ""
  }])) as DashboardModuleStates);
  const llmTrendDays = ref(7);
  const overview = ref<DashboardOverview>(createEmptyOverview());
  const loading = computed(() => moduleStates.summary.loading);
  const moduleLoading = computed(() => ["reviewTrend", "riskDistribution", "rules"].some(key => moduleStates[key as ModuleKey].loading));
  const deferredLoading = computed(() => moduleStates.highRiskReviews.loading || moduleStates.llmQuality.loading);
  const llmQualityLoading = computed(() => moduleStates.llmQuality.loading);
  const healthLoading = computed(() => moduleStates.systemHealth.loading);
  const errorMessage = computed(() => moduleStates.summary.error);
  const lastHealthCheckAt = computed(() => moduleStates.systemHealth.lastSuccessAt || "-");
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
  const overviewMetricItems = computed<MetricGridItem[]>(() => overviewMetrics.value.map(metric => ({
    label: metric.label, value: metric.value, color: metric.color,
    note: `较上周${metric.trendType.includes("up") ? "上升" : "下降"} ${metric.trend}`,
    noteClass: metric.trendType === "up-danger" ? "trend danger" : "trend"
  })));
  const totalRuleHits = computed(() => ruleHits.value.reduce((total, item) => total + item.value, 0));

  const cancelModule = (key: ModuleKey) => {
    controllers[key]?.abort();
    delete controllers[key];
    moduleStates[key].loading = false;
  };
  const loadModule = async <T>(
    key: ModuleKey,
    fetcher: (options: ApiRequestOptions) => Promise<T>,
    apply: (result: T) => void,
    fallback: string,
    label = ""
  ) => {
    if (disposed) return false;
    cancelModule(key);
    const controller = new AbortController();
    controllers[key] = controller;
    const current = () => !disposed && controllers[key] === controller && !controller.signal.aborted;
    const state = moduleStates[key];
    state.loading = true;
    state.error = "";
    try {
      const result = await fetcher({ signal: controller.signal });
      if (!current()) return false;
      apply(result);
      state.lastSuccessAt = formatDateTime(new Date());
      state.lastSuccessLabel = label;
      return true;
    } catch (error) {
      if (!current()) return false;
      state.error = getErrorMessage(error, fallback);
      if (key === "systemHealth") ElMessage.warning(state.error);
      else ElMessage.error(state.error);
      return false;
    } finally {
      if (current()) state.loading = false;
    }
  };

  const loadOverview = async (options: { deferModules?: boolean } = {}) => {
    if (disposed) return;
    const requestSeq = ++overviewRequestSeq;
    moduleKeys.filter(key => key !== "summary").forEach(cancelModule);
    const loaded = await loadModule("summary", fetchDashboardSummary,
      result => { overview.value.overviewMetrics = result; }, "仪表盘数据加载失败");
    if (loaded && !disposed && requestSeq === overviewRequestSeq && !options.deferModules) {
      void loadDashboardModules(requestSeq);
      void loadDeferredModules(requestSeq);
      void loadSystemHealth(requestSeq);
    }
  };
  const loadDashboardModules = async (overviewSeq = overviewRequestSeq) => {
    if (disposed || overviewSeq !== overviewRequestSeq) return;
    await Promise.all([
      loadModule("reviewTrend", fetchDashboardReviewTrend,
        result => { overview.value.reviewTrend = result; }, "审查趋势加载失败"),
      loadModule("riskDistribution", fetchDashboardRiskDistribution,
        result => { overview.value.riskDistribution = result; }, "风险分布加载失败"),
      loadModule("rules", fetchDashboardRules, result => {
        overview.value.ruleHits = result.ruleHits;
        overview.value.failedRules = result.failedRules;
      }, "规则统计加载失败")
    ]);
  };
  const loadLlmQuality = async () => {
    const days = llmTrendDays.value;
    await loadModule("llmQuality", options => fetchDashboardLlmQuality(days, options), result => {
      overview.value.llmQualityByModel = result.byModel;
      overview.value.llmQualityByRepository = result.byRepository;
      overview.value.llmQualityTrend = result.trend;
    }, "LLM 质量数据加载失败", `${days} 天窗口`);
  };
  const loadDeferredModules = async (overviewSeq = overviewRequestSeq) => {
    if (disposed || overviewSeq !== overviewRequestSeq) return;
    await Promise.all([
      loadModule("highRiskReviews", fetchDashboardHighRiskReviews,
        result => { overview.value.highRiskReviews = result; }, "高风险审查加载失败"),
      loadLlmQuality()
    ]);
  };
  const loadSystemHealth = async (overviewSeq = overviewRequestSeq) => {
    if (disposed || overviewSeq !== overviewRequestSeq) return;
    await loadModule("systemHealth", fetchSystemHealthSummary,
      result => { overview.value.systemHealth = result; }, "系统健康检查加载失败");
  };
  const updateLlmTrendDays = (days: number) => {
    if (disposed || !llmTrendWindowOptions.some(option => option.value === days)) return;
    llmTrendDays.value = days;
    void loadLlmQuality();
  };
  const dispose = () => {
    disposed = true;
    overviewRequestSeq++;
    moduleKeys.forEach(cancelModule);
  };
  if (getCurrentScope()) onScopeDispose(dispose);

  return {
    loading, moduleLoading, deferredLoading, llmQualityLoading, healthLoading, errorMessage,
    moduleStates, lastHealthCheckAt, llmTrendDays, llmTrendWindowOptions, overviewMetricItems, overviewMetrics,
    reviewTrend, riskDistribution, ruleHits, totalRuleHits, highRiskReviews, failedRules, systemHealth,
    llmQualityByModel, llmQualityByRepository, llmQualityTrend, loadOverview, loadDashboardModules,
    loadDeferredModules, loadLlmQuality, loadSystemHealth, updateLlmTrendDays
  };
};
