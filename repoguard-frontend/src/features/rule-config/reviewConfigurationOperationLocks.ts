import { ref } from "vue";

// A write owns its lock until the request and confirmation reads settle, even after a context switch.
export const createReviewConfigurationOperationLocks = () => {
  const ruleIds = ref(new Set<string>());
  const strategyBusy = ref(false);
  const tryRule = (id: string) => {
    if (ruleIds.value.has(id)) return;
    ruleIds.value.add(id);
    let released = false;
    return () => { if (!released) { released = true; ruleIds.value.delete(id); } };
  };
  const tryStrategy = () => {
    if (strategyBusy.value) return;
    strategyBusy.value = true;
    let released = false;
    return () => { if (!released) { released = true; strategyBusy.value = false; } };
  };
  return { ruleIds, strategyBusy, tryRule, tryStrategy };
};
export type ReviewConfigurationOperationLocks = ReturnType<typeof createReviewConfigurationOperationLocks>;
