import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchReviewRuleVersions, fetchReviewStrategyVersions, rollbackReviewRule, rollbackReviewStrategy } from "@/api/config";
import { createLatestPolicyHistoryLoader } from "@/features/rule-config/latestPolicyHistoryLoader";
import { createReviewConfigurationOperationLocks, type ReviewConfigurationOperationLocks } from "@/features/rule-config/reviewConfigurationOperationLocks";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import type { ReviewRuleConfig, ReviewRulePolicyVersion, ReviewStrategyPolicy } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type ReviewPolicyHistoryOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<boolean | void>;
  rules: Ref<ReviewRuleConfig[]>;
  strategyPolicy: Ref<ReviewStrategyPolicy | null>;
  rulesCurrent?: Readonly<Ref<boolean>>;
  cancelRulesRead?: () => void;
  invalidateRules?: () => void;
  operationLocks?: ReviewConfigurationOperationLocks;
};
const validVersion = (value: number) => Number.isSafeInteger(value) && value > 0;

export const useReviewPolicyHistory = ({ canManage, reloadRules, rules, strategyPolicy,
  rulesCurrent = ref(true), cancelRulesRead = () => undefined, invalidateRules = () => undefined,
  operationLocks = createReviewConfigurationOperationLocks() }: ReviewPolicyHistoryOptions) => {
  const ruleVersionDialogVisible = ref(false); const strategyVersionDialogVisible = ref(false);
  const ruleHistoryLoading = ref(false); const strategyHistoryLoading = ref(false);
  const rollbackSavingId = ref(""); const selectedRuleId = ref("");
  const ruleVersions = ref<ReviewRulePolicyVersion[]>([]); const strategyVersions = ref<ReviewStrategyPolicy[]>([]);
  const ruleHistoryNextCursor = ref<string | null>(null); const strategyHistoryNextCursor = ref<string | null>(null);
  const ruleHistoryHasMore = ref(false); const strategyHistoryHasMore = ref(false);
  const ruleHistoryCurrent = ref(false); const strategyHistoryCurrent = ref(false);
  const ruleHistoryError = ref(""); const strategyHistoryError = ref("");
  const ruleRollbackNotice = ref(""); const strategyRollbackNotice = ref("");
  const ruleRollbackError = ref(""); const strategyRollbackError = ref("");
  const ruleHistoryLoader = createLatestPolicyHistoryLoader(value => { ruleHistoryLoading.value = value; });
  const strategyHistoryLoader = createLatestPolicyHistoryLoader(value => { strategyHistoryLoading.value = value; });
  let disposed = false; let contextRevision = 0; let ruleRevision = 0; let strategyRevision = 0;
  let ruleHistoryVersion = 0; let strategyHistoryVersion = 0;
  const contextIdentity = () => JSON.stringify([currentUser.value?.id, activeTenant.value]);
  const contextCurrent = (revision: number) => !disposed && canManage.value && revision === contextRevision;
  const ruleCurrent = (context: number, revision: number, id: string) => contextCurrent(context)
    && ruleVersionDialogVisible.value && revision === ruleRevision && selectedRuleId.value === id;
  const strategyCurrent = (context: number, revision: number) => contextCurrent(context)
    && strategyVersionDialogVisible.value && revision === strategyRevision;
  const cancelRuleHistoryRequest = () => { ruleHistoryLoader.cancel(); ruleHistoryCurrent.value = false; };
  const cancelStrategyHistoryRequest = () => { strategyHistoryLoader.cancel(); strategyHistoryCurrent.value = false; };
  const clearRule = () => {
    ruleRevision += 1; cancelRuleHistoryRequest(); ruleVersions.value = []; ruleHistoryVersion = 0;
    ruleHistoryNextCursor.value = null; ruleHistoryHasMore.value = false; ruleHistoryError.value = ""; ruleRollbackNotice.value = ""; ruleRollbackError.value = "";
  };
  const clearStrategy = () => {
    strategyRevision += 1; cancelStrategyHistoryRequest(); strategyVersions.value = []; strategyHistoryVersion = 0;
    strategyHistoryNextCursor.value = null; strategyHistoryHasMore.value = false; strategyHistoryError.value = ""; strategyRollbackNotice.value = ""; strategyRollbackError.value = "";
  };
  watch([ruleVersionDialogVisible, selectedRuleId], clearRule, { flush: "sync" });
  watch(strategyVersionDialogVisible, clearStrategy, { flush: "sync" });
  const clearContext = () => {
    contextRevision += 1; clearRule(); clearStrategy();
    ruleVersionDialogVisible.value = false; strategyVersionDialogVisible.value = false; selectedRuleId.value = "";
  };
  watch([canManage, activeTenant, () => currentUser.value?.id], clearContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });

  const loadRuleHistoryPage = async (cursor?: string, append = false) => {
    const id = selectedRuleId.value; const context = contextRevision; const revision = ruleRevision;
    if (!ruleCurrent(context, revision, id) || !id) return false;
    const expected = rules.value.find(rule => rule.id === id)?.policyVersion ?? 0;
    ruleHistoryCurrent.value = false; ruleHistoryError.value = "";
    try {
      const accepted = await ruleHistoryLoader.load(
        signal => fetchReviewRuleVersions(id, { cursor }, { signal }), page => {
          if (!ruleCurrent(context, revision, id)) return;
          ruleVersions.value = append ? [...ruleVersions.value, ...page.items] : page.items;
          ruleHistoryNextCursor.value = page.nextCursor ?? null; ruleHistoryHasMore.value = Boolean(page.hasMore);
          ruleHistoryVersion = expected; ruleHistoryCurrent.value = true;
        });
      return accepted && ruleCurrent(context, revision, id);
    } catch (error) {
      if (ruleCurrent(context, revision, id)) ruleHistoryError.value = getErrorMessage(error, "规则版本历史加载失败，请重试刷新。");
      return false;
    }
  };
  const loadStrategyHistoryPage = async (cursor?: string, append = false) => {
    const context = contextRevision; const revision = strategyRevision;
    if (!strategyCurrent(context, revision)) return false;
    const expected = strategyPolicy.value?.snapshotId ?? 0;
    strategyHistoryCurrent.value = false; strategyHistoryError.value = "";
    try {
      const accepted = await strategyHistoryLoader.load(
        signal => fetchReviewStrategyVersions({ cursor }, { signal }), page => {
          if (!strategyCurrent(context, revision)) return;
          strategyVersions.value = append ? [...strategyVersions.value, ...page.items] : page.items;
          strategyHistoryNextCursor.value = page.nextCursor ?? null; strategyHistoryHasMore.value = Boolean(page.hasMore);
          strategyHistoryVersion = expected; strategyHistoryCurrent.value = true;
        });
      return accepted && strategyCurrent(context, revision);
    } catch (error) {
      if (strategyCurrent(context, revision)) strategyHistoryError.value = getErrorMessage(error, "策略版本历史加载失败，请重试刷新。");
      return false;
    }
  };
  const openRuleVersions = async (rule: ReviewRuleConfig) => {
    if (disposed || !canManage.value || !rulesCurrent.value || !rules.value.some(row => row.id === rule.id)) return;
    clearRule(); selectedRuleId.value = rule.id; ruleVersionDialogVisible.value = true;
    await loadRuleHistoryPage();
  };
  const openStrategyVersions = async () => {
    if (disposed || !canManage.value || !rulesCurrent.value || !strategyPolicy.value) return;
    clearStrategy(); strategyVersionDialogVisible.value = true; await loadStrategyHistoryPage();
  };
  const refreshRuleVersions = () => loadRuleHistoryPage();
  const refreshStrategyVersions = () => loadStrategyHistoryPage();
  const loadMoreRuleVersions = async () => {
    if (ruleHistoryLoading.value || !ruleHistoryCurrent.value || !ruleHistoryHasMore.value || !ruleHistoryNextCursor.value) return;
    await loadRuleHistoryPage(ruleHistoryNextCursor.value, true);
  };
  const loadMoreStrategyVersions = async () => {
    if (strategyHistoryLoading.value || !strategyHistoryCurrent.value || !strategyHistoryHasMore.value || !strategyHistoryNextCursor.value) return;
    await loadStrategyHistoryPage(strategyHistoryNextCursor.value, true);
  };
  const canRollbackRule = (version: number) => !disposed && canManage.value && rulesCurrent.value && ruleVersionDialogVisible.value
    && ruleHistoryCurrent.value && !ruleHistoryLoading.value && !rollbackSavingId.value && validVersion(version)
    && !operationLocks.ruleIds.value.has(selectedRuleId.value)
    && rules.value.some(rule => rule.id === selectedRuleId.value && validVersion(rule.policyVersion) && rule.policyVersion === ruleHistoryVersion)
    && ruleVersions.value.some(row => row.policyVersion === version && !row.active);
  const canRollbackStrategy = (snapshot: number) => !disposed && canManage.value && rulesCurrent.value && strategyVersionDialogVisible.value
    && strategyHistoryCurrent.value && !strategyHistoryLoading.value && !rollbackSavingId.value && !operationLocks.strategyBusy.value
    && validVersion(snapshot) && !!strategyPolicy.value && validVersion(strategyPolicy.value.snapshotId)
    && strategyPolicy.value.snapshotId === strategyHistoryVersion && strategyVersions.value.some(row => row.snapshotId === snapshot && !row.active);
  const readFreshRules = async () => {
    try { return await reloadRules() !== false && rulesCurrent.value; } catch { return false; }
  };
  const refreshAfterWrite = async (current: () => boolean, history: () => Promise<boolean>, error: Ref<string>) => {
    const refreshed = await readFreshRules();
    if (!current()) return;
    if (!refreshed) { error.value = "规则与策略尚未刷新成功，请刷新列表后再操作。"; return; }
    await history();
  };
  const finishOperation = (release: () => void, context: number, identity: string) => {
    release(); rollbackSavingId.value = "";
    if (!disposed && context !== contextRevision && identity === contextIdentity()) invalidateRules();
  };

  const rollbackRuleVersion = async (policyVersion: number) => {
    if (!canRollbackRule(policyVersion)) return;
    const id = selectedRuleId.value; const expected = rules.value.find(rule => rule.id === id)!.policyVersion;
    const context = contextRevision; const revision = ruleRevision; const identity = contextIdentity();
    const current = () => ruleCurrent(context, revision, id);
    const release = operationLocks.tryRule(id); if (!release) return;
    rollbackSavingId.value = `rule-${policyVersion}`; ruleHistoryCurrent.value = false;
    ruleHistoryError.value = ""; ruleRollbackNotice.value = ""; ruleRollbackError.value = ""; cancelRulesRead();
    try {
      let saved: ReviewRuleConfig;
      try { saved = await rollbackReviewRule(id, policyVersion, expected); }
      catch (error) {
        if (!contextCurrent(context)) return;
        invalidateRules();
        if (current()) { ruleRollbackError.value = getErrorMessage(error, "规则策略回滚失败"); ElMessage.error(ruleRollbackError.value); }
        // Refresh the CAS baseline; never repeat the write automatically.
        await readFreshRules(); return;
      }
      if (!contextCurrent(context)) return;
      invalidateRules();
      if (current()) {
        if (saved?.id === id && validVersion(saved.policyVersion) && saved.policyVersion > expected) {
          ruleRollbackNotice.value = "回滚已生成新的规则版本。"; ElMessage.success("规则策略已生成新的回滚版本");
        } else { ruleRollbackError.value = "回滚请求已返回，但规则标识或版本不一致，请刷新确认。"; }
      }
      await refreshAfterWrite(current, refreshRuleVersions, ruleHistoryError);
    } finally { finishOperation(release, context, identity); }
  };
  const rollbackStrategyVersion = async (snapshotId: number) => {
    if (!canRollbackStrategy(snapshotId)) return;
    const expected = strategyPolicy.value!.snapshotId; const context = contextRevision; const revision = strategyRevision;
    const identity = contextIdentity(); const current = () => strategyCurrent(context, revision);
    const release = operationLocks.tryStrategy(); if (!release) return;
    rollbackSavingId.value = `strategy-${snapshotId}`; strategyHistoryCurrent.value = false;
    strategyHistoryError.value = ""; strategyRollbackNotice.value = ""; strategyRollbackError.value = ""; cancelRulesRead();
    try {
      let saved: ReviewStrategyPolicy;
      try { saved = await rollbackReviewStrategy(snapshotId, expected); }
      catch (error) {
        if (!contextCurrent(context)) return;
        invalidateRules();
        if (current()) { strategyRollbackError.value = getErrorMessage(error, "审查策略回滚失败"); ElMessage.error(strategyRollbackError.value); }
        await readFreshRules(); return;
      }
      if (!contextCurrent(context)) return;
      invalidateRules();
      if (current()) {
        if (validVersion(saved?.snapshotId) && saved.snapshotId > expected && saved.active) {
          if (!strategyPolicy.value || strategyPolicy.value.snapshotId <= saved.snapshotId) strategyPolicy.value = saved;
          strategyRollbackNotice.value = "回滚已生成新的策略快照。"; ElMessage.success("审查策略已生成新的回滚快照");
        } else { strategyRollbackError.value = "回滚请求已返回，但策略快照不一致，请刷新确认。"; }
      }
      await refreshAfterWrite(current, refreshStrategyVersions, strategyHistoryError);
    } finally { finishOperation(release, context, identity); }
  };
  return { rollbackSavingId, ruleHistoryHasMore, ruleHistoryLoading, ruleVersionDialogVisible, ruleVersions, selectedRuleId,
    strategyHistoryHasMore, strategyHistoryLoading, strategyVersionDialogVisible, strategyVersions, ruleHistoryCurrent, strategyHistoryCurrent,
    ruleHistoryError, strategyHistoryError, ruleRollbackNotice, strategyRollbackNotice, ruleRollbackError, strategyRollbackError, canRollbackRule, canRollbackStrategy,
    cancelRuleHistoryRequest, cancelStrategyHistoryRequest, loadMoreRuleVersions, loadMoreStrategyVersions,
    openRuleVersions, openStrategyVersions, refreshRuleVersions, refreshStrategyVersions, rollbackRuleVersion, rollbackStrategyVersion };
};
