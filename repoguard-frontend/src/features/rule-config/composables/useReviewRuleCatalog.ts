import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import { fetchReviewRules } from "@/api/config";
import { canManage, currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import type {
  ReviewQualityGroup,
  ReviewRuleConfig,
  ReviewStrategyPolicy,
  SimpleMetric
} from "@/types";
import { getErrorMessage } from "@/utils/errors";

export const useReviewRuleCatalog = ({ canRead = canManage }: { canRead?: Readonly<Ref<boolean>> } = {}) => {
  const severityFilter = ref("");
  const statusFilter = ref("");
  const keyword = ref("");
  const loading = ref(false);
  const errorMessage = ref("");
  const rules = ref<ReviewRuleConfig[]>([]);
  const metrics = ref<SimpleMetric[]>([]);
  const qualityGroups = ref<ReviewQualityGroup[]>([]);
  const strategyPolicy = ref<ReviewStrategyPolicy | null>(null);
  const rulesNeedRefresh = ref(true);
  let controller: AbortController | undefined;
  let revision = 0;
  let disposed = false;
  const rulesCurrent = computed(() => !disposed && canRead.value && !loading.value && !rulesNeedRefresh.value);

  const cancelRulesRead = () => {
    controller?.abort();
    controller = undefined;
    loading.value = false;
  };
  const invalidateRules = (clear = false) => {
    cancelRulesRead();
    rulesNeedRefresh.value = true;
    errorMessage.value = "";
    if (clear) {
      rules.value = []; metrics.value = []; qualityGroups.value = []; strategyPolicy.value = null;
    }
  };
  const clearContext = () => { revision += 1; invalidateRules(true); };
  watch([canRead, activeTenant, () => currentUser.value?.id], clearContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });

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
    if (disposed || !canRead.value) return false;
    invalidateRules();
    const requestController = new AbortController();
    controller = requestController;
    const version = revision;
    const current = () => !disposed && canRead.value && version === revision
      && controller === requestController && !requestController.signal.aborted;
    loading.value = true;
    try {
      const response = await fetchReviewRules({ signal: requestController.signal });
      if (!current()) return false;
      metrics.value = response.metrics;
      rules.value = response.rules;
      qualityGroups.value = response.qualityGroups ?? [];
      strategyPolicy.value = response.strategyPolicy ?? null;
      rulesNeedRefresh.value = false;
      return true;
    } catch (error) {
      if (current()) errorMessage.value = getErrorMessage(error, "规则加载失败");
      return false;
    } finally {
      if (current()) loading.value = false;
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
    rulesCurrent,
    rulesNeedRefresh,
    severityFilter,
    statusFilter,
    strategyPolicy,
    topRuleDocs,
    loadRules,
    cancelRulesRead,
    invalidateRules
  };
};
