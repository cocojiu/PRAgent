import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { updateReviewStrategyEnforcement } from "@/api/config";
import type { EnforcementMode, ReviewStrategyPolicy } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";

type ReviewStrategyGovernanceOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<boolean | void>;
  strategyPolicy: Ref<ReviewStrategyPolicy | null>;
  rulesCurrent?: Readonly<Ref<boolean>>;
  cancelRulesRead?: () => void;
  invalidateRules?: () => void;
};

export const useReviewStrategyGovernance = ({
  canManage,
  reloadRules,
  strategyPolicy,
  rulesCurrent = ref(true),
  cancelRulesRead = () => undefined,
  invalidateRules = () => undefined
}: ReviewStrategyGovernanceOptions) => {
  const strategyTargetMode = ref<EnforcementMode>("observe");
  const strategySaving = ref(false);
  const strategySaveError = ref("");
  const strategyRefreshError = ref("");
  const strategySaveNotice = ref("");
  const savedMode = ref<EnforcementMode>();
  let disposed = false; let revision = 0; let initialized = false;
  let applyingPolicy = false; let synchronizingTarget = false; let draftEdited = false;
  const contextIdentity = () => JSON.stringify([currentUser.value?.id, activeTenant.value]);
  const syncTarget = (mode: EnforcementMode) => {
    synchronizingTarget = true;
    try { strategyTargetMode.value = mode; } finally { synchronizingTarget = false; }
  };
  watch(strategyTargetMode, () => { if (!synchronizingTarget) draftEdited = true; }, { flush: "sync" });
  const strategyHasUnsavedChanges = computed(() => initialized && strategyTargetMode.value !== savedMode.value);
  const canSaveStrategy = computed(() => !disposed && canManage.value && initialized && rulesCurrent.value && !strategySaving.value
    && !!strategyPolicy.value && Number.isSafeInteger(strategyPolicy.value.snapshotId) && strategyPolicy.value.snapshotId > 0
    && strategyHasUnsavedChanges.value && ["observe", "comment", "block"].includes(strategyTargetMode.value));

  watch(
    strategyPolicy,
    policy => {
      if (applyingPolicy) return;
      if (!policy) { savedMode.value = undefined; return; }
      const preserveDraft = initialized && draftEdited;
      savedMode.value = policy.enforcementMode;
      initialized = true;
      if (!preserveDraft) syncTarget(policy.enforcementMode);
      if (strategyTargetMode.value === policy.enforcementMode && !strategySaving.value) draftEdited = false;
    },
    { immediate: true, flush: "sync" }
  );
  const clearContext = () => {
    revision += 1; initialized = false; draftEdited = false; savedMode.value = undefined; syncTarget("observe");
    strategySaveError.value = ""; strategyRefreshError.value = ""; strategySaveNotice.value = "";
  };
  watch([canManage, activeTenant, () => currentUser.value?.id], clearContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });
  const readFreshRules = async () => {
    try { return await reloadRules() !== false && rulesCurrent.value; } catch { return false; }
  };

  const saveStrategyEnforcement = async () => {
    if (!canSaveStrategy.value) return;
    const submitted = strategyTargetMode.value; const expectedSnapshotId = strategyPolicy.value!.snapshotId;
    const version = revision; const context = contextIdentity();
    const current = () => !disposed && canManage.value && version === revision;
    strategySaving.value = true; strategySaveError.value = ""; strategyRefreshError.value = ""; strategySaveNotice.value = "";
    cancelRulesRead();
    try {
      let saved: ReviewStrategyPolicy;
      try {
        saved = await updateReviewStrategyEnforcement({ enforcementMode: submitted, expectedSnapshotId });
      } catch (error) {
        if (!current()) return;
        invalidateRules(); strategySaveError.value = getErrorMessage(error, "策略处置模式更新失败");
        ElMessage.error(strategySaveError.value); await readFreshRules(); return;
      }
      if (!current()) return;
      invalidateRules();
      const acknowledged = Number.isSafeInteger(saved?.snapshotId) && saved.snapshotId >= expectedSnapshotId && saved.enforcementMode === submitted;
      if (acknowledged) {
        const nextChoice = strategyTargetMode.value;
        const newerSnapshotKnown = !!strategyPolicy.value && strategyPolicy.value.snapshotId > saved.snapshotId;
        if (!newerSnapshotKnown) {
          applyingPolicy = true;
          try { strategyPolicy.value = saved; } finally { applyingPolicy = false; }
          savedMode.value = saved.enforcementMode; initialized = true;
          syncTarget(nextChoice === submitted ? saved.enforcementMode : nextChoice);
          draftEdited = strategyTargetMode.value !== savedMode.value;
        }
        strategySaveNotice.value = newerSnapshotKnown ? "本次模式已保存，当前策略已有更新，请核对最新快照。"
          : strategyHasUnsavedChanges.value ? "本次模式已保存，新的选择尚未保存。" : "本次模式已保存。";
        ElMessage.success("审查策略处置模式已更新");
      } else {
        strategySaveError.value = "模式更新请求已返回，但快照或处置模式不一致，请刷新确认。";
        ElMessage.warning(strategySaveError.value);
      }
      const refreshed = await readFreshRules();
      if (current() && !refreshed) strategyRefreshError.value = acknowledged ? "本次模式已保存，规则与策略尚未刷新成功。" : "规则与策略尚未刷新成功，请刷新确认。";
    } finally {
      strategySaving.value = false;
      if (!disposed && version !== revision && context === contextIdentity()) invalidateRules();
    }
  };

  return {
    strategySaving,
    strategyTargetMode,
    canSaveStrategy,
    strategyHasUnsavedChanges,
    strategySaveError,
    strategyRefreshError,
    strategySaveNotice,
    saveStrategyEnforcement
  };
};
