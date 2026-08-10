import { computed, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewRules } from "@/api/config";
import type {
  ReviewQualityGroup,
  ReviewRuleConfig,
  ReviewStrategyPolicy,
  SimpleMetric
} from "@/types";
import { getErrorMessage } from "@/utils/errors";

export const useReviewRuleCatalog = () => {
  const severityFilter = ref("");
  const statusFilter = ref("");
  const keyword = ref("");
  const loading = ref(false);
  const errorMessage = ref("");
  const rules = ref<ReviewRuleConfig[]>([]);
  const metrics = ref<SimpleMetric[]>([]);
  const qualityGroups = ref<ReviewQualityGroup[]>([]);
  const strategyPolicy = ref<ReviewStrategyPolicy | null>(null);

  const filteredRules = computed(() => {
    const query = keyword.value.trim().toLowerCase();
    return rules.value.filter((rule) => {
      const matchesSeverity = !severityFilter.value || rule.severity === severityFilter.value;
      const matchesStatus = !statusFilter.value || rule.status === statusFilter.value;
      const matchesKeyword =
        !query
        || rule.id.toLowerCase().includes(query)
        || rule.name.toLowerCase().includes(query)
        || rule.scope.toLowerCase().includes(query)
        || (rule.applicableLanguages ?? "").toLowerCase().includes(query)
        || (rule.filePatterns ?? "").toLowerCase().includes(query);
      return matchesSeverity && matchesStatus && matchesKeyword;
    });
  });

  const topRuleDocs = computed(() => rules.value.slice(0, 4));

  const loadRules = async () => {
    loading.value = true;
    errorMessage.value = "";
    try {
      const response = await fetchReviewRules();
      metrics.value = response.metrics;
      rules.value = response.rules;
      qualityGroups.value = response.qualityGroups ?? [];
      strategyPolicy.value = response.strategyPolicy ?? null;
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "规则加载失败");
      ElMessage.error(errorMessage.value);
    } finally {
      loading.value = false;
    }
  };

  return {
    errorMessage,
    filteredRules,
    keyword,
    loading,
    metrics,
    qualityGroups,
    rules,
    severityFilter,
    statusFilter,
    strategyPolicy,
    topRuleDocs,
    loadRules
  };
};
