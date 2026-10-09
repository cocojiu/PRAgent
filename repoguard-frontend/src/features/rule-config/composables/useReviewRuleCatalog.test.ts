import { effectScope, ref, type EffectScope } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ReviewRuleConfig, ReviewRulesResponse, ReviewStrategyPolicy } from "@/types";
import { useReviewRuleCatalog } from "./useReviewRuleCatalog";

const api = vi.hoisted(() => ({ fetchReviewRules: vi.fn() }));
vi.mock("@/api/config", () => api);
const scopes: EffectScope[] = [];
const response = (id = "RG-A") => ({
  rules: [{ id, name: id, scope: "Java", applicableLanguages: "Java", severity: "high", status: "enabled" } as ReviewRuleConfig],
  metrics: [{ label: "规则", value: id, note: "", color: "blue" }], qualityGroups: [],
  strategyPolicy: { snapshotId: 9 } as ReviewStrategyPolicy
}) as ReviewRulesResponse;
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const setup = () => {
  const canRead = ref(true); const scope = effectScope(); scopes.push(scope);
  return { canRead, scope, state: scope.run(() => useReviewRuleCatalog({ canRead }))! };
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchReviewRules.mockResolvedValue(response());
});
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); currentUser.value = undefined; });

describe("rule catalog query ownership", () => {
  it.each(["resolve", "reject"])("ignores late %s without clearing a newer loading state", async outcome => {
    const { state } = setup(); const old = deferred<ReviewRulesResponse>(); const latest = deferred<ReviewRulesResponse>();
    api.fetchReviewRules.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const first = state.loadRules(); const second = state.loadRules();
    expect(api.fetchReviewRules.mock.calls[0]![0].signal.aborted).toBe(true);
    if (outcome === "resolve") old.resolve(response("OLD")); else old.reject(new Error("old failure"));
    expect(await first).toBe(false); expect(state.loading.value).toBe(true); expect(state.errorMessage.value).toBe("");
    expect(state.rules.value).toEqual([]);
    latest.resolve(response("CURRENT")); expect(await second).toBe(true);
    expect(state.rules.value[0]!.id).toBe("CURRENT"); expect(state.metrics.value[0]!.value).toBe("CURRENT");
    expect(state.rulesCurrent.value).toBe(true); expect(state.loading.value).toBe(false);
  });

  it("keeps old rows visibly unconfirmed after failure and accepts explicit retry", async () => {
    const { state } = setup(); await state.loadRules();
    const read = deferred<ReviewRulesResponse>(); api.fetchReviewRules.mockReturnValueOnce(read.promise);
    const pending = state.loadRules(); expect(state.rules.value[0]!.id).toBe("RG-A"); expect(state.rulesCurrent.value).toBe(false);
    read.reject(new Error("current offline")); expect(await pending).toBe(false);
    expect(state.rulesNeedRefresh.value).toBe(true); expect(state.errorMessage.value).toBe("current offline");
    expect(state.strategyPolicy.value?.snapshotId).toBe(9);
    api.fetchReviewRules.mockResolvedValueOnce(response("RETRY")); expect(await state.loadRules()).toBe(true);
    expect(state.errorMessage.value).toBe(""); expect(state.rules.value[0]!.id).toBe("RETRY");
  });

  it.each(["tenant", "account", "permission", "dispose"])("clears prior context and ignores a pending result on %s", async change => {
    const { state, canRead, scope } = setup(); await state.loadRules();
    const read = deferred<ReviewRulesResponse>(); api.fetchReviewRules.mockReturnValueOnce(read.promise);
    const pending = state.loadRules(); const signal = api.fetchReviewRules.mock.calls[1]![0].signal;
    if (change === "tenant") setActiveTenant("other");
    else if (change === "account") currentUser.value = { ...currentUser.value!, id: 2 };
    else if (change === "permission") canRead.value = false;
    else scope.stop();
    expect(signal.aborted).toBe(true); expect(state.rules.value).toEqual([]); expect(state.metrics.value).toEqual([]);
    expect(state.strategyPolicy.value).toBeNull(); expect(state.qualityGroups.value).toEqual([]);
    read.resolve(response("LATE")); expect(await pending).toBe(false); expect(state.rules.value).toEqual([]);
    expect(state.loading.value).toBe(false); expect(state.errorMessage.value).toBe("");
  });

  it("does not adopt old results when the same tenant returns", async () => {
    const { state } = setup(); const read = deferred<ReviewRulesResponse>(); api.fetchReviewRules.mockReturnValueOnce(read.promise);
    const old = state.loadRules(); setActiveTenant("other"); clearActiveTenant();
    expect(await state.loadRules()).toBe(true); read.resolve(response("OLD")); expect(await old).toBe(false);
    expect(state.rules.value[0]!.id).toBe("RG-A");
  });

  it("does not issue reads without permission or after disposal", async () => {
    const { state, canRead, scope } = setup(); canRead.value = false;
    expect(await state.loadRules()).toBe(false); expect(api.fetchReviewRules).not.toHaveBeenCalled();
    canRead.value = true; scope.stop(); expect(await state.loadRules()).toBe(false);
    expect(api.fetchReviewRules).not.toHaveBeenCalled();
  });

  it("invalidates accepted records and cancels any interim read after a write", async () => {
    const { state } = setup(); await state.loadRules();
    const read = deferred<ReviewRulesResponse>(); api.fetchReviewRules.mockReturnValueOnce(read.promise);
    const pending = state.loadRules(); state.invalidateRules();
    expect(api.fetchReviewRules.mock.calls[1]![0].signal.aborted).toBe(true);
    read.resolve(response("INTERIM")); expect(await pending).toBe(false);
    expect(state.rules.value[0]!.id).toBe("RG-A"); expect(state.rulesCurrent.value).toBe(false);
  });
});
