import { ref, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { updateReviewStrategyEnforcement } from "@/api/config";
import type { EnforcementMode, ReviewStrategyPolicy } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type ReviewStrategyGovernanceOptions = {
  canManage: Readonly<Ref<boolean>>;
  reloadRules: () => Promise<void>;
  strategyPolicy: Ref<ReviewStrategyPolicy | null>;
};

export const useReviewStrategyGovernance = ({
  canManage,
  reloadRules,
  strategyPolicy
}: ReviewStrategyGovernanceOptions) => {
  const strategyTargetMode = ref<EnforcementMode>("observe");
  const strategySaving = ref(false);

  watch(
    strategyPolicy,
    policy => {
      strategyTargetMode.value = policy?.enforcementMode ?? "observe";
    },
    { immediate: true, flush: "sync" }
  );

  const saveStrategyEnforcement = async () => {
    if (!canManage.value || !strategyPolicy.value) {
      return;
    }
    strategySaving.value = true;
    try {
      strategyPolicy.value = await updateReviewStrategyEnforcement({
        enforcementMode: strategyTargetMode.value,
        expectedSnapshotId: strategyPolicy.value.snapshotId
      });
      ElMessage.success("审查策略处置模式已更新");
      await reloadRules();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "策略处置模式更新失败"));
      await reloadRules();
    } finally {
      strategySaving.value = false;
    }
  };

  return {
    strategySaving,
    strategyTargetMode,
    saveStrategyEnforcement
  };
};
