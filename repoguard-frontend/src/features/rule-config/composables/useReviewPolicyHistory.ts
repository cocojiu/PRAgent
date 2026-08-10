import { onBeforeUnmount, ref, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchReviewRuleVersions,
  fetchReviewStrategyVersions,
  rollbackReviewRule,
  rollbackReviewStrategy
} from "@/api/config";
import { createLatestPolicyHistoryLoader } from "@/features/rule-config/latestPolicyHistoryLoader";
import type {
  ReviewRuleConfig,
  ReviewRulePolicyVersion,
  ReviewStrategyPolicy
} from "@/types";
import { getErrorMessage } from "@/utils/errors";

type ReviewPolicyHistoryOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<void>;
  rules: Ref<ReviewRuleConfig[]>;
  strategyPolicy: Ref<ReviewStrategyPolicy | null>;
};

export const useReviewPolicyHistory = ({
  canManage,
  reloadRules,
  rules,
  strategyPolicy
}: ReviewPolicyHistoryOptions) => {
  const ruleVersionDialogVisible = ref(false);
  const strategyVersionDialogVisible = ref(false);
  const ruleHistoryLoading = ref(false);
  const strategyHistoryLoading = ref(false);
  const rollbackSavingId = ref("");
  const selectedRuleId = ref("");
  const ruleVersions = ref<ReviewRulePolicyVersion[]>([]);
  const strategyVersions = ref<ReviewStrategyPolicy[]>([]);
  const ruleHistoryNextCursor = ref<string | null>(null);
  const ruleHistoryHasMore = ref(false);
  const strategyHistoryNextCursor = ref<string | null>(null);
  const strategyHistoryHasMore = ref(false);
  const ruleHistoryLoader = createLatestPolicyHistoryLoader(value => {
    ruleHistoryLoading.value = value;
  });
  const strategyHistoryLoader = createLatestPolicyHistoryLoader(value => {
    strategyHistoryLoading.value = value;
  });

  const loadRuleHistoryPage = (
    ruleId: string,
    cursor?: string,
    append = false
  ) => ruleHistoryLoader.load(
    signal => fetchReviewRuleVersions(ruleId, { cursor }, { signal }),
    page => {
      if (selectedRuleId.value !== ruleId) {
        return;
      }
      ruleVersions.value = append ? [...ruleVersions.value, ...page.items] : page.items;
      ruleHistoryNextCursor.value = page.nextCursor ?? null;
      ruleHistoryHasMore.value = Boolean(page.hasMore);
    }
  );

  const loadStrategyHistoryPage = (
    cursor?: string,
    append = false
  ) => strategyHistoryLoader.load(
    signal => fetchReviewStrategyVersions({ cursor }, { signal }),
    page => {
      strategyVersions.value = append ? [...strategyVersions.value, ...page.items] : page.items;
      strategyHistoryNextCursor.value = page.nextCursor ?? null;
      strategyHistoryHasMore.value = Boolean(page.hasMore);
    }
  );

  const openRuleVersions = async (rule: ReviewRuleConfig) => {
    selectedRuleId.value = rule.id;
    ruleVersions.value = [];
    ruleHistoryNextCursor.value = null;
    ruleHistoryHasMore.value = false;
    ruleVersionDialogVisible.value = true;
    try {
      await loadRuleHistoryPage(rule.id);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "规则版本历史加载失败"));
    }
  };

  const openStrategyVersions = async () => {
    strategyVersions.value = [];
    strategyHistoryNextCursor.value = null;
    strategyHistoryHasMore.value = false;
    strategyVersionDialogVisible.value = true;
    try {
      await loadStrategyHistoryPage();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "策略版本历史加载失败"));
    }
  };

  const loadMoreRuleVersions = async () => {
    if (
      ruleHistoryLoading.value
      || !selectedRuleId.value
      || !ruleHistoryHasMore.value
      || !ruleHistoryNextCursor.value
    ) {
      return;
    }
    const ruleId = selectedRuleId.value;
    const cursor = ruleHistoryNextCursor.value;
    try {
      await loadRuleHistoryPage(ruleId, cursor, true);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "规则版本历史加载失败"));
    }
  };

  const loadMoreStrategyVersions = async () => {
    if (
      strategyHistoryLoading.value
      || !strategyHistoryHasMore.value
      || !strategyHistoryNextCursor.value
    ) {
      return;
    }
    const cursor = strategyHistoryNextCursor.value;
    try {
      await loadStrategyHistoryPage(cursor, true);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "策略版本历史加载失败"));
    }
  };

  const cancelRuleHistoryRequest = () => ruleHistoryLoader.cancel();
  const cancelStrategyHistoryRequest = () => strategyHistoryLoader.cancel();

  const rollbackRuleVersion = async (policyVersion: number) => {
    if (!canManage.value || !selectedRuleId.value) {
      return;
    }
    rollbackSavingId.value = `rule-${policyVersion}`;
    try {
      const activeRule = rules.value.find(rule => rule.id === selectedRuleId.value);
      if (!activeRule) {
        throw new Error("当前规则不存在，请刷新后重试");
      }
      await rollbackReviewRule(selectedRuleId.value, policyVersion, activeRule.policyVersion);
      ElMessage.success("规则策略已生成新的回滚版本");
      await reloadRules();
      await loadRuleHistoryPage(selectedRuleId.value);
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "规则策略回滚失败"));
      await reloadRules();
    } finally {
      rollbackSavingId.value = "";
    }
  };

  const rollbackStrategyVersion = async (snapshotId: number) => {
    if (!canManage.value) {
      return;
    }
    rollbackSavingId.value = `strategy-${snapshotId}`;
    try {
      if (!strategyPolicy.value) {
        throw new Error("当前策略不存在，请刷新后重试");
      }
      strategyPolicy.value = await rollbackReviewStrategy(snapshotId, strategyPolicy.value.snapshotId);
      ElMessage.success("审查策略已生成新的回滚快照");
      await reloadRules();
      await loadStrategyHistoryPage();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "审查策略回滚失败"));
      await reloadRules();
    } finally {
      rollbackSavingId.value = "";
    }
  };

  onBeforeUnmount(() => {
    cancelRuleHistoryRequest();
    cancelStrategyHistoryRequest();
  });

  return {
    rollbackSavingId,
    ruleHistoryHasMore,
    ruleHistoryLoading,
    ruleVersionDialogVisible,
    ruleVersions,
    selectedRuleId,
    strategyHistoryHasMore,
    strategyHistoryLoading,
    strategyVersionDialogVisible,
    strategyVersions,
    cancelRuleHistoryRequest,
    cancelStrategyHistoryRequest,
    loadMoreRuleVersions,
    loadMoreStrategyVersions,
    openRuleVersions,
    openStrategyVersions,
    rollbackRuleVersion,
    rollbackStrategyVersion
  };
};
