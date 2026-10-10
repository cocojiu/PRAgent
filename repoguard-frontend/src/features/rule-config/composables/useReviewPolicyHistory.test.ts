import { effectScope, ref, type EffectScope } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ReviewRuleConfig, ReviewRulePolicyVersion, ReviewStrategyPolicy } from "@/types";
import { createReviewConfigurationOperationLocks } from "@/features/rule-config/reviewConfigurationOperationLocks";
import { useReviewPolicyHistory } from "./useReviewPolicyHistory";
import { useReviewRuleEditor } from "./useReviewRuleEditor";
import { useReviewStrategyGovernance } from "./useReviewStrategyGovernance";
const api = vi.hoisted(() => ({ fetchReviewRuleVersions: vi.fn(), fetchReviewStrategyVersions: vi.fn(), rollbackReviewRule: vi.fn(),
  rollbackReviewStrategy: vi.fn(), updateReviewRule: vi.fn(), updateReviewRuleStatus: vi.fn(), updateReviewStrategyEnforcement: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const gate = { labeledSamples: 0, labeledHighRiskSamples: 0, precision: 0, falsePositiveRate: 0, anchorRate: 0,
  duplicateRate: 0, commentEligible: false, blockEligible: false, status: "INSUFFICIENT_SAMPLE", blockers: ["missing labels"] };
const rule = (id = "RG-A", policyVersion = 7): ReviewRuleConfig => ({ id, name: id, scope: "Java", applicableLanguages: "Java",
  filePatterns: "*.java", severity: "high", status: "enabled", hitCount: 2, confidence: "90", updatedAt: "2026-10-01T00:00:00Z",
  description: "description", positiveExample: "example", falsePositiveGuidance: "guidance", enforcementMode: "observe",
  detectorVersion: "detector-v1", detectorType: "BUILTIN", matcherExpression: "", exceptionPatterns: "", configVersion: 1, policyVersion, qualityGate: gate });
const policy = (snapshotId = 11, active = true): ReviewStrategyPolicy => ({ snapshotId, strategyVersion: 2, promptVersion: "prompt-v1",
  contextVersion: "context-v1", schemaVersion: "schema-v1", verifierVersion: "verifier-v1", aggregationVersion: "aggregation-v1",
  enforcementMode: "observe", replayVerified: false, active, changeType: "UPDATE", createdAt: "2026-10-01T00:00:00Z", qualityGate: gate });
const version = (policyVersion = 4, active = false): ReviewRulePolicyVersion => ({ ...rule(), policyVersion, active, changeType: "UPDATE", createdAt: "2026-10-01T00:00:00Z" });
const page = () => ({ items: [version()], hasMore: false });
const deferred = <T>() => { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
let scopes: EffectScope[] = [];
const setup = () => {
  const scope = effectScope(); scopes.push(scope);
  const canManage = ref(true); const rules = ref([rule(), rule("RG-B")]); const strategyPolicy = ref<ReviewStrategyPolicy | null>(policy());
  const rulesCurrent = ref(true); const reloadRules = vi.fn(async () => true); const invalidateRules = vi.fn(); const cancelRulesRead = vi.fn();
  const operationLocks = createReviewConfigurationOperationLocks(); const options = { canManage, rules, strategyPolicy, rulesCurrent, reloadRules, invalidateRules, cancelRulesRead, operationLocks };
  return { ...options, scope, history: scope.run(() => useReviewPolicyHistory(options))!, editor: scope.run(() => useReviewRuleEditor(options))!,
    strategy: scope.run(() => useReviewStrategyGovernance(options))! };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = undefined; clearActiveTenant();
  api.fetchReviewRuleVersions.mockResolvedValue(page()); api.fetchReviewStrategyVersions.mockResolvedValue({ items: [policy(6, false)], hasMore: false });
  api.rollbackReviewRule.mockResolvedValue(rule("RG-A", 8)); api.rollbackReviewStrategy.mockResolvedValue(policy(12));
});
afterEach(() => { scopes.forEach(scope => scope.stop()); scopes = []; currentUser.value = undefined; clearActiveTenant(); });

describe("history context and rollback ownership", () => {
  it("submits the selected immutable target with the accepted CAS baseline", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); await s.history.rollbackRuleVersion(4);
    expect(api.rollbackReviewRule).toHaveBeenCalledWith("RG-A", 4, 7); expect(s.cancelRulesRead).toHaveBeenCalledOnce();
    expect(s.history.ruleRollbackNotice.value).toContain("生成"); expect(s.reloadRules).toHaveBeenCalledOnce();
    await s.history.openStrategyVersions(); await s.history.rollbackStrategyVersion(6);
    expect(api.rollbackReviewStrategy).toHaveBeenCalledWith(6, 11); expect(s.strategyPolicy.value?.snapshotId).toBe(12);
  });
  it.each(["close", "target", "reopen"])("ignores the previous rule write UI after %s", async action => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); const d = deferred<ReviewRuleConfig>(); api.rollbackReviewRule.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackRuleVersion(4);
    if (action === "close") s.history.ruleVersionDialogVisible.value = false;
    else await s.history.openRuleVersions(s.rules.value[action === "target" ? 1 : 0]!);
    d.resolve(rule("RG-A", 8)); await pending;
    expect(messages.success).not.toHaveBeenCalled(); expect(s.history.ruleRollbackNotice.value).toBe("");
    expect(api.fetchReviewRuleVersions).toHaveBeenCalledTimes(action === "close" ? 1 : 2);
    if (action === "target") expect(s.history.selectedRuleId.value).toBe("RG-B");
  });
  it.each(["tenant", "permission", "account", "dispose"])("clears history and ignores delayed writes after %s", async change => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); const d = deferred<ReviewRuleConfig>(); api.rollbackReviewRule.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackRuleVersion(4);
    if (change === "tenant") setActiveTenant("other");
    if (change === "permission") s.canManage.value = false;
    if (change === "account") currentUser.value = { id: 2 } as NonNullable<typeof currentUser.value>;
    if (change === "dispose") s.scope.stop();
    expect(s.operationLocks.ruleIds.value.has("RG-A")).toBe(true);
    d.resolve(rule("RG-A", 8)); await pending;
    expect(s.history.ruleVersionDialogVisible.value).toBe(false); expect(s.history.ruleVersions.value).toEqual([]);
    expect(s.reloadRules).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled(); expect(s.operationLocks.ruleIds.value.size).toBe(0);
  });
  it("aborts read immediately on close and suppresses its late error", async () => {
    const s = setup(); const d = deferred<ReturnType<typeof page>>(); api.fetchReviewRuleVersions.mockReturnValueOnce(d.promise);
    const pending = s.history.openRuleVersions(s.rules.value[0]!); const signal = api.fetchReviewRuleVersions.mock.calls[0]![2].signal as AbortSignal;
    s.history.ruleVersionDialogVisible.value = false; expect(signal.aborted).toBe(true); d.reject(new Error("late")); await pending;
    expect(s.history.ruleHistoryError.value).toBe(""); expect(messages.error).not.toHaveBeenCalled();
  });
  it("accepts only the newest read for the reopened same target", async () => {
    const s = setup(); const old = deferred<ReturnType<typeof page>>(); api.fetchReviewRuleVersions.mockReturnValueOnce(old.promise);
    const pending = s.history.openRuleVersions(s.rules.value[0]!); api.fetchReviewRuleVersions.mockResolvedValueOnce({ items: [version(5)], hasMore: false });
    await s.history.openRuleVersions(s.rules.value[0]!); old.resolve(page()); await pending;
    expect(s.history.ruleVersions.value.map(row => row.policyVersion)).toEqual([5]); expect(s.history.ruleHistoryCurrent.value).toBe(true);
  });
  it("requires a current visible nonactive history target and valid catalog version", async () => {
    const s = setup(); await s.history.rollbackRuleVersion(4); await s.history.rollbackStrategyVersion(6);
    await s.history.openRuleVersions(s.rules.value[0]!);
    for (const value of [0, -1, 1.5, NaN, 999]) await s.history.rollbackRuleVersion(value);
    s.history.ruleVersions.value[0]!.active = true; await s.history.rollbackRuleVersion(4);
    s.history.ruleVersions.value[0]!.active = false; s.rules.value[0]!.policyVersion = 8; await s.history.rollbackRuleVersion(4);
    s.rules.value[0]!.policyVersion = 7; s.rulesCurrent.value = false; await s.history.rollbackRuleVersion(4);
    expect(api.rollbackReviewRule).not.toHaveBeenCalled(); expect(api.rollbackReviewStrategy).not.toHaveBeenCalled();
  });
  it("keeps write confirmation when catalog refresh fails and never repeats the POST", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); s.reloadRules.mockRejectedValueOnce(new Error("read failed"));
    await s.history.rollbackRuleVersion(4); expect(s.history.ruleRollbackNotice.value).toContain("生成");
    expect(s.history.ruleHistoryError.value).toContain("刷新"); expect(s.history.ruleHistoryCurrent.value).toBe(false);
    expect(messages.error).not.toHaveBeenCalled(); expect(api.rollbackReviewRule).toHaveBeenCalledOnce();
  });
  it("keeps write confirmation separate from history refresh failure and allows manual read retry", async () => {
    const s = setup(); await s.history.openStrategyVersions(); api.fetchReviewStrategyVersions.mockRejectedValueOnce(new Error("history read failed"));
    await s.history.rollbackStrategyVersion(6); expect(s.history.strategyRollbackNotice.value).toContain("生成");
    expect(s.history.strategyHistoryError.value).toBe("history read failed"); expect(s.history.canRollbackStrategy(6)).toBe(false);
    await s.history.refreshStrategyVersions(); expect(s.history.strategyHistoryCurrent.value).toBe(true); expect(s.history.strategyHistoryError.value).toBe("");
    expect(api.rollbackReviewStrategy).toHaveBeenCalledOnce();
  });
  it("refreshes after conflicts but requires explicit history refresh and preserves the write error", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); api.rollbackReviewRule.mockRejectedValueOnce(new Error("CAS conflict"));
    await s.history.rollbackRuleVersion(4); expect(s.history.ruleRollbackError.value).toBe("CAS conflict"); expect(s.reloadRules).toHaveBeenCalledOnce();
    expect(s.history.canRollbackRule(4)).toBe(false); await s.history.rollbackRuleVersion(4); expect(api.rollbackReviewRule).toHaveBeenCalledOnce();
    await s.history.refreshRuleVersions(); expect(s.history.canRollbackRule(4)).toBe(true);
  });
  it("does not acknowledge a mismatched rule response", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); api.rollbackReviewRule.mockResolvedValueOnce(rule("RG-B", 8));
    await s.history.rollbackRuleVersion(4); expect(s.history.ruleRollbackError.value).toContain("不一致"); expect(messages.success).not.toHaveBeenCalled();
    expect(s.history.ruleRollbackNotice.value).toBe("");
  });
  it("does not replace a newer known snapshot with a late acknowledged rollback", async () => {
    const s = setup(); await s.history.openStrategyVersions(); const d = deferred<ReviewStrategyPolicy>(); api.rollbackReviewStrategy.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackStrategyVersion(6); s.strategyPolicy.value = policy(15); d.resolve(policy(12)); await pending;
    expect(s.strategyPolicy.value?.snapshotId).toBe(15);
  });
  it("keeps the strategy response out of a closed dialog and reads the current catalog", async () => {
    const s = setup(); await s.history.openStrategyVersions(); const d = deferred<ReviewStrategyPolicy>(); api.rollbackReviewStrategy.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackStrategyVersion(6); s.history.strategyVersionDialogVisible.value = false; d.resolve(policy(12)); await pending;
    expect(s.strategyPolicy.value?.snapshotId).toBe(11); expect(messages.success).not.toHaveBeenCalled(); expect(s.reloadRules).toHaveBeenCalledOnce();
  });
  it("holds same-rule exclusion through confirmation reads and allows another rule status update", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); const d = deferred<ReviewRuleConfig>(); api.rollbackReviewRule.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackRuleVersion(4); await s.history.rollbackRuleVersion(4);
    s.editor.openEditDialog(s.rules.value[0]!); s.editor.ruleForm.name = "draft"; await s.editor.saveRule(); await s.editor.toggleRule(s.rules.value[0]!, "disabled");
    expect(api.updateReviewRule).not.toHaveBeenCalled(); expect(api.updateReviewRuleStatus).not.toHaveBeenCalled();
    const read = deferred<boolean>(); s.reloadRules.mockReturnValueOnce(read.promise); d.resolve(rule("RG-A", 8)); await Promise.resolve();
    expect(s.operationLocks.ruleIds.value.has("RG-A")).toBe(true); expect(s.editor.canChangeRule("RG-B")).toBe(true);
    read.resolve(true); await pending; expect(s.operationLocks.ruleIds.value.has("RG-A")).toBe(false); expect(api.rollbackReviewRule).toHaveBeenCalledOnce();
  });
  it("excludes strategy application during rollback and rollback during application", async () => {
    const s = setup(); await s.history.openStrategyVersions(); const d = deferred<ReviewStrategyPolicy>(); api.rollbackReviewStrategy.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackStrategyVersion(6); s.strategy.strategyTargetMode.value = "comment"; await s.strategy.saveStrategyEnforcement();
    expect(api.updateReviewStrategyEnforcement).not.toHaveBeenCalled(); d.resolve(policy(12)); await pending;
    await s.history.refreshStrategyVersions(); const write = deferred<ReviewStrategyPolicy>(); api.updateReviewStrategyEnforcement.mockReturnValueOnce(write.promise);
    const application = s.strategy.saveStrategyEnforcement(); await s.history.rollbackStrategyVersion(6);
    expect(api.rollbackReviewStrategy).toHaveBeenCalledOnce(); write.resolve({ ...policy(13), enforcementMode: "comment" }); await application;
  });
  it("retains old locks across leaving and returning a tenant until the request settles", async () => {
    const s = setup(); await s.history.openRuleVersions(s.rules.value[0]!); const d = deferred<ReviewRuleConfig>(); api.rollbackReviewRule.mockReturnValueOnce(d.promise);
    const pending = s.history.rollbackRuleVersion(4); setActiveTenant("other"); clearActiveTenant();
    await s.history.openRuleVersions(s.rules.value[0]!); expect(s.history.canRollbackRule(4)).toBe(false);
    d.resolve(rule("RG-A", 8)); await pending; expect(s.invalidateRules).toHaveBeenCalledOnce(); expect(s.reloadRules).not.toHaveBeenCalled();
  });
  it("invalidates stale histories after failed pagination until manual refresh", async () => {
    const s = setup(); api.fetchReviewRuleVersions.mockResolvedValueOnce({ items: [version()], hasMore: true, nextCursor: "next" });
    await s.history.openRuleVersions(s.rules.value[0]!); api.fetchReviewRuleVersions.mockRejectedValueOnce(new Error("page failed"));
    await s.history.loadMoreRuleVersions(); expect(s.history.ruleVersions.value).toHaveLength(1); expect(s.history.canRollbackRule(4)).toBe(false);
    await s.history.refreshRuleVersions(); expect(s.history.canRollbackRule(4)).toBe(true);
  });
});
