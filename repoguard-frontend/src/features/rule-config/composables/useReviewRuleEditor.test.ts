import { effectScope, ref, type EffectScope } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ReviewRuleConfig, ReviewRuleConfigRequest, ReviewRuleStatusRequest } from "@/types";
import { useReviewRuleEditor } from "./useReviewRuleEditor";

const api = vi.hoisted(() => ({ createReviewRule: vi.fn(), updateReviewRule: vi.fn(), updateReviewRuleStatus: vi.fn() }));
const messages = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const rule = (overrides: Partial<ReviewRuleConfig> = {}): ReviewRuleConfig => ({
  id: "RG-A", name: "original", scope: "Java", applicableLanguages: "Java", filePatterns: "*.java",
  severity: "high", status: "enabled", hitCount: 2, confidence: "90", updatedAt: "2026-10-01T00:00:00Z",
  description: "description", positiveExample: "example", falsePositiveGuidance: "guidance", enforcementMode: "observe",
  detectorVersion: "detector-v1", detectorType: "REGEX", matcherExpression: "token:save", exceptionPatterns: "",
  configVersion: 1, policyVersion: 3,
  qualityGate: { labeledSamples: 0, labeledHighRiskSamples: 0, precision: 0, falsePositiveRate: 0, anchorRate: 0,
    duplicateRate: 0, commentEligible: false, blockEligible: false, status: "INSUFFICIENT_SAMPLE", blockers: ["missing labels"] },
  ...overrides
});
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const setup = () => {
  const canManage = ref(true); const rules = ref([rule(), rule({ id: "RG-B", name: "second" })]);
  const rulesCurrent = ref(true); const server = ref([...rules.value]);
  const persist = (saved: ReviewRuleConfig) => { server.value = [...server.value.filter(row => row.id !== saved.id), saved]; return saved; };
  const reloadRules = vi.fn(async () => { rules.value = [...server.value]; rulesCurrent.value = true; return true; });
  const invalidateRules = vi.fn(() => { rulesCurrent.value = false; }); const cancelRulesRead = vi.fn();
  api.updateReviewRule.mockImplementation((id: string, version: number, payload: ReviewRuleConfigRequest) =>
    Promise.resolve(persist(rule({ ...payload, id, confidence: String(payload.confidence), policyVersion: version + 1 }))));
  api.createReviewRule.mockImplementation((payload: ReviewRuleConfigRequest) =>
    Promise.resolve(persist(rule({ ...payload, confidence: String(payload.confidence), policyVersion: 1 }))));
  api.updateReviewRuleStatus.mockImplementation((id: string, payload: ReviewRuleStatusRequest) =>
    Promise.resolve(persist(rule({ ...server.value.find(row => row.id === id)!, status: payload.status, policyVersion: payload.expectedPolicyVersion + 1 }))));
  const scope = effectScope(); scopes.push(scope);
  const state = scope.run(() => useReviewRuleEditor({ canManage, rules, rulesCurrent, reloadRules, invalidateRules, cancelRulesRead }))!;
  state.openEditDialog(rules.value[0]!);
  return { state, scope, canManage, rules, rulesCurrent, server, persist, reloadRules, invalidateRules, cancelRulesRead };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
});

describe("rule save and status operation exclusion", () => {
  it("excludes a status update while the same rule save is pending", async () => {
    const { state, rules, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "draft"; const pending = state.saveRule(); await state.toggleRule(rules.value[0]!, "disabled");
    expect(api.updateReviewRuleStatus).not.toHaveBeenCalled(); expect(state.busyRuleIds.value).toEqual(["RG-A"]);
    write.resolve(persist(rule({ name: "draft", policyVersion: 4 }))); await pending;
    expect(state.busyRuleIds.value).toEqual([]); expect(state.canChangeRule("RG-A")).toBe(true);
  });

  it("excludes saving and version refresh while the same status update is pending", async () => {
    const { state, rules, reloadRules, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRuleStatus.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "draft"; const pending = state.toggleRule(rules.value[0]!, "disabled");
    expect(state.canSaveRule.value).toBe(false); expect(state.canRefreshRule.value).toBe(false);
    await state.saveRule(); await state.refreshEditingRule(); expect(api.updateReviewRule).not.toHaveBeenCalled(); expect(reloadRules).not.toHaveBeenCalled();
    write.resolve(persist(rule({ status: "disabled", policyVersion: 4 }))); await pending;
    expect(state.ruleForm.name).toBe("draft"); expect(state.ruleVersionChanged.value).toBe(true);
    await state.refreshEditingRule(); expect(state.ruleForm.name).toBe("draft"); expect(state.ruleForm.status).toBe("disabled");
    await state.saveRule(); expect(api.updateReviewRule.mock.calls[0]![1]).toBe(4);
  });

  it("keeps the same-rule lock through the authoritative post-write read", async () => {
    const { state, rules, reloadRules, server, rulesCurrent } = setup(); const read = deferred<boolean>();
    reloadRules.mockImplementationOnce(async () => { await read.promise; rules.value = [...server.value]; rulesCurrent.value = true; return true; });
    const pending = state.toggleRule(rules.value[0]!, "disabled"); await flush();
    expect(state.busyRuleIds.value).toEqual(["RG-A"]); await state.toggleRule(rule(), "disabled");
    expect(api.updateReviewRuleStatus).toHaveBeenCalledOnce(); read.resolve(true); await pending;
    expect(state.busyRuleIds.value).toEqual([]); expect(rules.value.find(row => row.id === "RG-A")!.status).toBe("disabled");
  });

  it("allows different rules to update in parallel and releases only each operation's lock", async () => {
    const { state, rules, persist } = setup(); const first = deferred<ReviewRuleConfig>(); const second = deferred<ReviewRuleConfig>();
    api.updateReviewRuleStatus.mockImplementation((id: string) => id === "RG-A" ? first.promise : second.promise);
    const a = state.toggleRule(rules.value[0]!, "disabled"); const b = state.toggleRule(rules.value[1]!, "disabled");
    expect(api.updateReviewRuleStatus).toHaveBeenCalledTimes(2); expect(state.busyRuleIds.value).toEqual(["RG-A", "RG-B"]);
    first.resolve(persist(rule({ status: "disabled", policyVersion: 4 }))); await a;
    expect(state.busyRuleIds.value).toEqual(["RG-B"]); expect(state.statusSavingId.value).toBe("RG-B");
    second.resolve(persist(rule({ id: "RG-B", name: "second", status: "disabled", policyVersion: 4 }))); await b;
    expect(state.busyRuleIds.value).toEqual([]); expect(state.statusSavingId.value).toBe("");
  });

  it("permits an independent rule status update during another rule save", async () => {
    const { state, rules, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "draft"; const pending = state.saveRule(); await state.toggleRule(rules.value[1]!, "disabled");
    expect(api.updateReviewRuleStatus).toHaveBeenCalledWith("RG-B", { status: "disabled", expectedPolicyVersion: 3 });
    expect(state.busyRuleIds.value).toEqual(["RG-A"]); write.resolve(persist(rule({ name: "draft", policyVersion: 4 }))); await pending;
  });

  it("does not mutate the old row or reinsert it into a newer result", async () => {
    const { state, rules, reloadRules } = setup(); const original = rules.value[0]!;
    const write = deferred<ReviewRuleConfig>(); api.updateReviewRuleStatus.mockReturnValueOnce(write.promise);
    const pending = state.toggleRule(original, "disabled");
    reloadRules.mockImplementationOnce(async () => { rules.value = [rule({ id: "RG-B" })]; return true; });
    write.resolve(rule({ status: "disabled", policyVersion: 4 })); await pending;
    expect(original.status).toBe("enabled"); expect(original.policyVersion).toBe(3);
    expect(rules.value.map(row => row.id)).toEqual(["RG-B"]);
  });

  it("separates status acknowledgment from read failure and blocks operations on unconfirmed rows", async () => {
    const { state, rules, reloadRules } = setup(); reloadRules.mockResolvedValueOnce(false);
    await state.toggleRule(rules.value[0]!, "disabled");
    expect(messages.success).toHaveBeenCalledOnce(); expect(messages.error).not.toHaveBeenCalled(); expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining("状态已更新"));
    expect(state.canChangeRule("RG-A")).toBe(false); await state.toggleRule(rule(), "disabled"); expect(api.updateReviewRuleStatus).toHaveBeenCalledOnce();
  });

  it("retains a current status error and refreshes before a user-triggered retry", async () => {
    const { state, rules } = setup(); api.updateReviewRuleStatus.mockRejectedValueOnce(new Error("status offline"));
    await state.toggleRule(rules.value[0]!, "disabled"); expect(state.ruleOperationErrors.value["RG-A"]).toBe("status offline");
    expect(rules.value[0]!.status).toBe("enabled"); expect(state.canChangeRule("RG-A")).toBe(true);
    await state.toggleRule(rules.value[0]!, "disabled"); expect(api.updateReviewRuleStatus).toHaveBeenCalledTimes(2); expect(state.ruleOperationErrors.value["RG-A"]).toBeUndefined();
  });

  it.each(["wrong id", "wrong status", "invalid version"])("does not acknowledge a %s status reply", async issue => {
    const { state, rules } = setup(); api.updateReviewRuleStatus.mockResolvedValueOnce(rule({
      id: issue === "wrong id" ? "RG-B" : "RG-A", status: issue === "wrong status" ? "enabled" : "disabled", policyVersion: issue === "invalid version" ? 0 : 4
    }));
    await state.toggleRule(rules.value[0]!, "disabled"); expect(state.ruleOperationErrors.value["RG-A"]).toContain("不一致"); expect(messages.success).not.toHaveBeenCalled();
    expect(rules.value[0]!.status).toBe("enabled");
  });

  it.each(["tenant", "account", "permission", "dispose"])("keeps the real pending lock and discards old status results after %s", async change => {
    const { state, rules, canManage, scope, reloadRules } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRuleStatus.mockReturnValueOnce(write.promise);
    const pending = state.toggleRule(rules.value[0]!, "disabled");
    if (change === "tenant") setActiveTenant("other");
    else if (change === "account") currentUser.value = { ...currentUser.value!, id: 2 };
    else if (change === "permission") canManage.value = false;
    else scope.stop();
    expect(state.busyRuleIds.value).toEqual(["RG-A"]); write.resolve(rule({ status: "disabled", policyVersion: 4 })); await pending;
    expect(state.busyRuleIds.value).toEqual([]); expect(reloadRules).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled();
  });

  it("ignores a late status failure after the original tenant returns and invalidates the interim read", async () => {
    const { state, rules, rulesCurrent, invalidateRules } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRuleStatus.mockReturnValueOnce(write.promise);
    const pending = state.toggleRule(rules.value[0]!, "disabled"); setActiveTenant("other"); clearActiveTenant();
    await state.toggleRule(rules.value[0]!, "disabled"); expect(api.updateReviewRuleStatus).toHaveBeenCalledOnce();
    write.reject(new Error("old offline")); await pending; expect(messages.error).not.toHaveBeenCalled();
    expect(invalidateRules).toHaveBeenCalledOnce(); expect(rulesCurrent.value).toBe(false); expect(state.ruleOperationErrors.value).toEqual({});
  });

  it("does not issue invalid, stale, unchanged or unauthorized status writes", async () => {
    const { state, rules, canManage } = setup();
    await state.toggleRule(rule({ policyVersion: 2 }), "disabled"); await state.toggleRule(rules.value[0]!, "enabled");
    await state.toggleRule(rules.value[0]!, "invalid"); await state.toggleRule(rule({ id: "MISSING" }), "disabled");
    canManage.value = false; await state.toggleRule(rules.value[0]!, "disabled"); expect(api.updateReviewRuleStatus).not.toHaveBeenCalled();
  });
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("rule editor draft and target ownership", () => {
  it("merges server normalization while preserving edits made during the submitted save", async () => {
    const { state, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "  submitted  "; const pending = state.saveRule();
    state.ruleForm.name = "later"; state.ruleForm.description = "later description";
    write.resolve(persist(rule({ name: "submitted", scope: "normalized scope", policyVersion: 4 }))); await pending;
    expect(api.updateReviewRule).toHaveBeenCalledWith("RG-A", 3, expect.objectContaining({ name: "submitted", description: "description" }));
    expect(state.ruleForm.name).toBe("later"); expect(state.ruleForm.description).toBe("later description");
    expect(state.ruleForm.scope).toBe("normalized scope"); expect(state.editingPolicyVersion.value).toBe(4);
    expect(state.dialogVisible.value).toBe(true); expect(state.ruleHasUnsavedChanges.value).toBe(true);
    expect(state.canSaveRule.value).toBe(true); expect(api.updateReviewRule).toHaveBeenCalledOnce();
    await state.saveRule(); expect(api.updateReviewRule.mock.calls[1]![1]).toBe(4); expect(state.dialogVisible.value).toBe(false);
  });

  it("keeps edits made while the post-save list read is pending", async () => {
    const { state, reloadRules, server, rules, rulesCurrent } = setup(); const read = deferred<boolean>();
    reloadRules.mockImplementationOnce(async () => { const accepted = await read.promise; rules.value = [...server.value]; rulesCurrent.value = true; return accepted; });
    state.ruleForm.name = "submitted"; const pending = state.saveRule(); await flush();
    expect(reloadRules).toHaveBeenCalledOnce(); state.ruleForm.name = "after acknowledgment";
    read.resolve(true); await pending; expect(state.ruleForm.name).toBe("after acknowledgment"); expect(state.dialogVisible.value).toBe(true);
  });

  it("updates the known created rule for later edits instead of creating twice", async () => {
    const { state, persist } = setup(); state.openCreateDialog();
    Object.assign(state.ruleForm, { id: "rg-new", name: "new", scope: "Java", description: "description", applicableLanguages: "Java", filePatterns: "*.java" });
    const write = deferred<ReviewRuleConfig>(); api.createReviewRule.mockReturnValueOnce(write.promise);
    const pending = state.saveRule(); state.ruleForm.name = "newer draft";
    write.resolve(persist(rule({ id: "RG-NEW", name: "new", policyVersion: 1 }))); await pending;
    expect(state.editingRuleId.value).toBe("RG-NEW"); expect(state.ruleForm.id).toBe("RG-NEW");
    expect(state.ruleForm.name).toBe("newer draft"); await state.saveRule();
    expect(api.createReviewRule).toHaveBeenCalledOnce(); expect(api.updateReviewRule).toHaveBeenCalledWith("RG-NEW", 1, expect.anything());
  });

  it.each(["resolve", "reject"])("does not alter a later editor on old %s", async outcome => {
    const { state, rules, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "submitted"; const pending = state.saveRule();
    state.dialogVisible.value = false; state.openEditDialog(rules.value[1]!); state.ruleForm.name = "second draft";
    if (outcome === "resolve") write.resolve(persist(rule({ name: "submitted", policyVersion: 4 }))); else write.reject(new Error("old failure"));
    await pending; expect(state.editingRuleId.value).toBe("RG-B"); expect(state.ruleForm.name).toBe("second draft");
    expect(state.dialogVisible.value).toBe(true); expect(state.ruleSaveError.value).toBe("");
    expect(messages.success).not.toHaveBeenCalled(); expect(messages.error).not.toHaveBeenCalled();
  });

  it("does not close a reopened editor for the same rule", async () => {
    const { state, rules, persist } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "submitted"; const pending = state.saveRule();
    state.dialogVisible.value = false; state.openEditDialog(rules.value[0]!); state.ruleForm.name = "reopened draft";
    write.resolve(persist(rule({ name: "submitted", policyVersion: 4 }))); await pending;
    expect(state.dialogVisible.value).toBe(true); expect(state.ruleForm.name).toBe("reopened draft");
    expect(state.editingPolicyVersion.value).toBe(3); expect(state.ruleVersionChanged.value).toBe(true);
  });

  it("keeps failed drafts and requires explicit merge with the newest policy version", async () => {
    const { state, persist } = setup(); state.ruleForm.name = "my draft";
    api.updateReviewRule.mockRejectedValueOnce(new Error("version conflict")); persist(rule({ name: "other edit", description: "new server description", policyVersion: 4 }));
    await state.saveRule(); expect(state.ruleSaveError.value).toBe("version conflict"); expect(state.ruleForm.name).toBe("my draft");
    expect(state.editingPolicyVersion.value).toBe(3); expect(state.canSaveRule.value).toBe(false);
    await state.saveRule(); expect(api.updateReviewRule).toHaveBeenCalledOnce();
    await state.refreshEditingRule(); expect(state.ruleForm.name).toBe("my draft"); expect(state.ruleForm.description).toBe("new server description");
    expect(state.editingPolicyVersion.value).toBe(4); expect(state.ruleSaveError.value).toBe("");
    await state.saveRule(); expect(api.updateReviewRule.mock.calls[1]![1]).toBe(4);
  });

  it("keeps the submitted draft after a normal failure and permits one explicit retry", async () => {
    const { state } = setup(); state.ruleForm.name = "draft"; api.updateReviewRule.mockRejectedValueOnce(new Error("offline"));
    await state.saveRule(); expect(state.ruleForm.name).toBe("draft"); expect(state.dialogVisible.value).toBe(true);
    expect(state.canSaveRule.value).toBe(true); await state.saveRule(); expect(api.updateReviewRule).toHaveBeenCalledTimes(2);
  });

  it("separates an acknowledged save from a failed read and never repeats the write during refresh", async () => {
    const { state, reloadRules } = setup(); state.ruleForm.name = "saved"; reloadRules.mockResolvedValueOnce(false);
    await state.saveRule(); expect(state.ruleSaveError.value).toBe(""); expect(state.ruleRefreshError.value).toContain("本次提交已保存");
    expect(messages.success).toHaveBeenCalledOnce(); expect(messages.error).not.toHaveBeenCalled(); expect(state.dialogVisible.value).toBe(true);
    await state.refreshEditingRule(); expect(api.updateReviewRule).toHaveBeenCalledOnce(); expect(state.ruleRefreshError.value).toBe("");
  });

  it.each(["tenant", "account", "permission", "dispose"])("clears drafts and ignores completion after %s", async change => {
    const { state, canManage, scope, persist, reloadRules } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "draft"; const pending = state.saveRule();
    if (change === "tenant") setActiveTenant("other");
    else if (change === "account") currentUser.value = { ...currentUser.value!, id: 2 };
    else if (change === "permission") canManage.value = false;
    else scope.stop();
    expect(state.dialogVisible.value).toBe(false); expect(state.ruleForm.name).toBe(""); expect(state.saving.value).toBe(true);
    write.resolve(persist(rule({ name: "saved", policyVersion: 4 }))); await pending;
    expect(reloadRules).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled(); expect(state.ruleSaveError.value).toBe("");
  });

  it("invalidates interim records when an old write settles after returning to its original context", async () => {
    const { state, rules, rulesCurrent, persist, invalidateRules } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "submitted"; const pending = state.saveRule(); setActiveTenant("other"); clearActiveTenant();
    state.openEditDialog(rules.value[0]!); state.ruleForm.name = "new context draft";
    await state.saveRule(); expect(api.updateReviewRule).toHaveBeenCalledOnce();
    write.resolve(persist(rule({ name: "saved", policyVersion: 4 }))); await pending;
    expect(invalidateRules).toHaveBeenCalledOnce(); expect(rulesCurrent.value).toBe(false);
    expect(state.ruleForm.name).toBe("new context draft"); expect(messages.success).not.toHaveBeenCalled();
  });

  it.each(["wrong id", "missing version"])("does not adopt an unconfirmed %s response", async issue => {
    const { state } = setup(); state.ruleForm.name = "draft";
    api.updateReviewRule.mockResolvedValueOnce(rule(issue === "wrong id" ? { id: "RG-B", policyVersion: 4 } : { policyVersion: 0 }));
    await state.saveRule(); expect(state.editingRuleId.value).toBe("RG-A"); expect(state.editingPolicyVersion.value).toBe(3);
    expect(state.ruleForm.name).toBe("draft"); expect(state.ruleSaveError.value).toContain("不一致"); expect(messages.success).not.toHaveBeenCalled();
  });

  it("ignores duplicate saves and prevents writes with an unconfirmed list", async () => {
    const { state, persist, rulesCurrent } = setup(); const write = deferred<ReviewRuleConfig>(); api.updateReviewRule.mockReturnValueOnce(write.promise);
    state.ruleForm.name = "draft"; const pending = state.saveRule(); await state.saveRule(); expect(api.updateReviewRule).toHaveBeenCalledOnce();
    write.resolve(persist(rule({ name: "draft", policyVersion: 4 }))); await pending;
    state.openEditDialog(rule()); state.ruleForm.name = "next"; rulesCurrent.value = false; await state.saveRule();
    expect(api.updateReviewRule).toHaveBeenCalledOnce();
  });

  it("preserves edits made during an explicit version refresh", async () => {
    const { state, reloadRules, rules, rulesCurrent } = setup(); state.ruleForm.name = "draft";
    const read = deferred<boolean>(); reloadRules.mockImplementationOnce(async () => {
      await read.promise; rules.value = [rule({ description: "new server description", policyVersion: 8 })]; rulesCurrent.value = true; return true;
    });
    const pending = state.refreshEditingRule(); state.ruleForm.name = "later draft"; read.resolve(true); await pending;
    expect(state.ruleForm.name).toBe("later draft"); expect(state.ruleForm.description).toBe("new server description"); expect(state.editingPolicyVersion.value).toBe(8);
  });

  it("keeps the current draft when explicit refresh fails or the rule is absent", async () => {
    const { state, reloadRules, server } = setup(); state.ruleForm.name = "draft"; reloadRules.mockResolvedValueOnce(false);
    await state.refreshEditingRule(); expect(state.ruleRefreshError.value).toContain("读取失败"); expect(state.ruleForm.name).toBe("draft");
    server.value = []; await state.refreshEditingRule(); expect(state.ruleRefreshError.value).toContain("不存在"); expect(state.editingPolicyVersion.value).toBe(3);
  });
});
