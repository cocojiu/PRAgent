import { effectScope, ref, type EffectScope } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { EnforcementMode, ReviewEnforcementModeRequest, ReviewStrategyPolicy } from "@/types";
import { useReviewStrategyGovernance } from "./useReviewStrategyGovernance";

const api = vi.hoisted(() => ({ updateReviewStrategyEnforcement: vi.fn() }));
const messages = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const policy = (overrides: Partial<ReviewStrategyPolicy> = {}): ReviewStrategyPolicy => ({
  snapshotId: 9, strategyVersion: 2, promptVersion: "prompt-v1", contextVersion: "context-v1", schemaVersion: "schema-v1",
  verifierVersion: "verifier-v1", aggregationVersion: "aggregation-v1", enforcementMode: "observe", replayVerified: true,
  active: true, changeType: "UPDATE", createdAt: "2026-10-01T00:00:00Z",
  qualityGate: { labeledSamples: 0, labeledHighRiskSamples: 0, precision: 0, falsePositiveRate: 0, anchorRate: 0, duplicateRate: 0,
    commentEligible: false, blockEligible: false, status: "INSUFFICIENT_SAMPLE", blockers: ["missing labels"] }, ...overrides
});
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const setup = (initial = policy()) => {
  const canManage = ref(true); const rulesCurrent = ref(true); const strategyPolicy = ref<ReviewStrategyPolicy | null>(initial);
  const server = ref(initial); const persist = (saved: ReviewStrategyPolicy) => { server.value = saved; return saved; };
  const reloadRules = vi.fn(async () => { strategyPolicy.value = { ...server.value }; rulesCurrent.value = true; return true; });
  const invalidateRules = vi.fn(() => { rulesCurrent.value = false; }); const cancelRulesRead = vi.fn();
  api.updateReviewStrategyEnforcement.mockImplementation((payload: ReviewEnforcementModeRequest) =>
    Promise.resolve(persist(policy({ ...server.value, snapshotId: payload.expectedSnapshotId + 1, enforcementMode: payload.enforcementMode }))));
  const scope = effectScope(); scopes.push(scope);
  const state = scope.run(() => useReviewStrategyGovernance({ canManage, strategyPolicy, rulesCurrent, reloadRules, invalidateRules, cancelRulesRead }))!;
  return { state, scope, canManage, rulesCurrent, strategyPolicy, server, persist, reloadRules, invalidateRules, cancelRulesRead };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("strategy mode draft and save ownership", () => {
  it("synchronizes initial and later clean policies without issuing a write", () => {
    const { state, strategyPolicy } = setup(policy({ enforcementMode: "comment" }));
    expect(state.strategyTargetMode.value).toBe("comment"); expect(state.canSaveStrategy.value).toBe(false);
    strategyPolicy.value = policy({ snapshotId: 10, enforcementMode: "block" });
    expect(state.strategyTargetMode.value).toBe("block"); expect(state.strategyHasUnsavedChanges.value).toBe(false);
    expect(api.updateReviewStrategyEnforcement).not.toHaveBeenCalled();
  });

  it("preserves a draft across an external read and submits the latest confirmed snapshot", async () => {
    const { state, strategyPolicy, server } = setup(); state.strategyTargetMode.value = "block";
    server.value = policy({ snapshotId: 12, enforcementMode: "comment" }); strategyPolicy.value = { ...server.value };
    expect(state.strategyTargetMode.value).toBe("block"); expect(state.strategyHasUnsavedChanges.value).toBe(true);
    expect(api.updateReviewStrategyEnforcement).not.toHaveBeenCalled(); await state.saveStrategyEnforcement();
    expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledWith({ enforcementMode: "block", expectedSnapshotId: 12 });
  });

  it("preserves a choice returned to the original mode while a fresh read replaces the server baseline", () => {
    const { state, strategyPolicy } = setup(); state.strategyTargetMode.value = "block"; state.strategyTargetMode.value = "observe";
    strategyPolicy.value = policy({ snapshotId: 10, enforcementMode: "comment" });
    expect(state.strategyTargetMode.value).toBe("observe"); expect(state.strategyHasUnsavedChanges.value).toBe(true);
  });

  it.each(["block", "observe"])("preserves later %s selection while acknowledging the submitted mode", async later => {
    const { state, persist } = setup(); const write = deferred<ReviewStrategyPolicy>(); api.updateReviewStrategyEnforcement.mockReturnValueOnce(write.promise);
    state.strategyTargetMode.value = "comment"; const pending = state.saveStrategyEnforcement(); state.strategyTargetMode.value = later as EnforcementMode;
    await state.saveStrategyEnforcement(); expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledOnce();
    write.resolve(persist(policy({ snapshotId: 10, enforcementMode: "comment" }))); await pending;
    expect(state.strategyTargetMode.value).toBe(later); expect(state.strategyHasUnsavedChanges.value).toBe(true);
    expect(state.canSaveStrategy.value).toBe(true); await state.saveStrategyEnforcement();
    expect(api.updateReviewStrategyEnforcement.mock.calls[1]![0]).toEqual({ enforcementMode: later, expectedSnapshotId: 10 });
  });

  it("keeps edits made while the acknowledged update's list read is pending", async () => {
    const { state, reloadRules, strategyPolicy, server, rulesCurrent } = setup(); const read = deferred<boolean>();
    reloadRules.mockImplementationOnce(async () => { await read.promise; strategyPolicy.value = { ...server.value }; rulesCurrent.value = true; return true; });
    state.strategyTargetMode.value = "comment"; const pending = state.saveStrategyEnforcement(); await flush();
    state.strategyTargetMode.value = "block"; read.resolve(true); await pending;
    expect(state.strategyTargetMode.value).toBe("block"); expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledOnce();
  });

  it("keeps the desired selection after a version conflict and requires manual retry", async () => {
    const { state, server } = setup(); state.strategyTargetMode.value = "block";
    server.value = policy({ snapshotId: 10, enforcementMode: "comment" }); api.updateReviewStrategyEnforcement.mockRejectedValueOnce(new Error("snapshot conflict"));
    await state.saveStrategyEnforcement(); expect(state.strategySaveError.value).toBe("snapshot conflict"); expect(state.strategyTargetMode.value).toBe("block");
    expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledOnce(); await state.saveStrategyEnforcement();
    expect(api.updateReviewStrategyEnforcement.mock.calls[1]![0]).toEqual({ enforcementMode: "block", expectedSnapshotId: 10 });
  });

  it("keeps server quality restrictions and a rejected promotion's draft", async () => {
    const { state, strategyPolicy } = setup(); state.strategyTargetMode.value = "comment";
    api.updateReviewStrategyEnforcement.mockRejectedValueOnce(new Error("At least one explicit labeled sample is required before COMMENT"));
    await state.saveStrategyEnforcement(); expect(state.strategySaveError.value).toContain("labeled sample");
    expect(state.strategyTargetMode.value).toBe("comment"); expect(strategyPolicy.value?.enforcementMode).toBe("observe");
    expect(strategyPolicy.value?.qualityGate).toEqual(policy().qualityGate); expect(messages.success).not.toHaveBeenCalled();
  });

  it("distinguishes save acknowledgment from refresh failure and never automatically resubmits", async () => {
    const { state, reloadRules, strategyPolicy } = setup(); state.strategyTargetMode.value = "comment"; reloadRules.mockResolvedValueOnce(false);
    await state.saveStrategyEnforcement(); expect(strategyPolicy.value?.snapshotId).toBe(10); expect(state.strategySaveError.value).toBe("");
    expect(state.strategyRefreshError.value).toContain("本次模式已保存"); expect(messages.error).not.toHaveBeenCalled();
    expect(state.canSaveStrategy.value).toBe(false); await reloadRules(); expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledOnce();
  });

  it("keeps a failed save distinct from a failing reload and leaves the choice unchanged", async () => {
    const { state, reloadRules } = setup(); state.strategyTargetMode.value = "block";
    api.updateReviewStrategyEnforcement.mockRejectedValueOnce(new Error("promotion refused")); reloadRules.mockRejectedValueOnce(new Error("read offline"));
    await state.saveStrategyEnforcement(); expect(state.strategySaveError.value).toBe("promotion refused"); expect(state.strategyTargetMode.value).toBe("block");
    expect(state.canSaveStrategy.value).toBe(false); expect(messages.error).toHaveBeenCalledOnce();
  });

  it.each(["wrong mode", "invalid snapshot", "older snapshot"])("does not acknowledge a %s reply", async issue => {
    const { state, strategyPolicy } = setup(); state.strategyTargetMode.value = "comment";
    api.updateReviewStrategyEnforcement.mockResolvedValueOnce(policy({ snapshotId: issue === "invalid snapshot" ? 0 : issue === "older snapshot" ? 8 : 10,
      enforcementMode: issue === "wrong mode" ? "observe" : "comment" }));
    await state.saveStrategyEnforcement(); expect(state.strategySaveError.value).toContain("不一致");
    expect(strategyPolicy.value?.snapshotId).toBe(9); expect(state.strategyTargetMode.value).toBe("comment"); expect(messages.success).not.toHaveBeenCalled();
  });

  it("does not replace a newer confirmed snapshot with a delayed update acknowledgment", async () => {
    const { state, strategyPolicy, server, reloadRules } = setup(); const write = deferred<ReviewStrategyPolicy>(); const read = deferred<boolean>();
    api.updateReviewStrategyEnforcement.mockReturnValueOnce(write.promise); reloadRules.mockImplementationOnce(() => read.promise);
    state.strategyTargetMode.value = "comment"; const pending = state.saveStrategyEnforcement();
    server.value = policy({ snapshotId: 12, enforcementMode: "block" }); strategyPolicy.value = { ...server.value };
    write.resolve(policy({ snapshotId: 10, enforcementMode: "comment" })); await flush();
    expect(strategyPolicy.value?.snapshotId).toBe(12); expect(state.strategyTargetMode.value).toBe("comment");
    expect(state.strategySaveNotice.value).toContain("已有更新"); read.resolve(false); await pending;
  });

  it.each(["tenant", "account", "permission", "dispose"])("clears the previous choice and ignores completion after %s", async change => {
    const { state, canManage, scope, reloadRules } = setup(); const write = deferred<ReviewStrategyPolicy>(); api.updateReviewStrategyEnforcement.mockReturnValueOnce(write.promise);
    state.strategyTargetMode.value = "comment"; const pending = state.saveStrategyEnforcement();
    if (change === "tenant") setActiveTenant("other");
    else if (change === "account") currentUser.value = { ...currentUser.value!, id: 2 };
    else if (change === "permission") canManage.value = false;
    else scope.stop();
    expect(state.strategyTargetMode.value).toBe("observe"); expect(state.strategySaving.value).toBe(true);
    write.resolve(policy({ snapshotId: 10, enforcementMode: "comment" })); await pending;
    expect(reloadRules).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled(); expect(state.canSaveStrategy.value).toBe(false);
  });

  it("ignores an old failure after returning to the original account and invalidates interim state", async () => {
    const { state, strategyPolicy, invalidateRules, rulesCurrent } = setup(); const write = deferred<ReviewStrategyPolicy>(); api.updateReviewStrategyEnforcement.mockReturnValueOnce(write.promise);
    state.strategyTargetMode.value = "comment"; const pending = state.saveStrategyEnforcement();
    currentUser.value = { ...currentUser.value!, id: 2 }; currentUser.value = { ...currentUser.value!, id: 1 };
    strategyPolicy.value = policy({ snapshotId: 20 }); state.strategyTargetMode.value = "block";
    await state.saveStrategyEnforcement(); expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledOnce();
    write.reject(new Error("old failure")); await pending;
    expect(state.strategySaveError.value).toBe(""); expect(state.strategyTargetMode.value).toBe("block"); expect(strategyPolicy.value?.snapshotId).toBe(20);
    expect(invalidateRules).toHaveBeenCalledOnce(); expect(rulesCurrent.value).toBe(false);
  });

  it.each(["unconfirmed", "permission", "missing policy", "invalid snapshot", "invalid mode"])("does not save with %s state", async invalid => {
    const { state, strategyPolicy, rulesCurrent, canManage } = setup(); state.strategyTargetMode.value = "comment";
    if (invalid === "unconfirmed") rulesCurrent.value = false;
    else if (invalid === "permission") canManage.value = false;
    else if (invalid === "missing policy") strategyPolicy.value = null;
    else if (invalid === "invalid snapshot") strategyPolicy.value = policy({ snapshotId: 0 });
    else state.strategyTargetMode.value = "invalid" as EnforcementMode;
    await state.saveStrategyEnforcement(); expect(api.updateReviewStrategyEnforcement).not.toHaveBeenCalled();
  });
});
