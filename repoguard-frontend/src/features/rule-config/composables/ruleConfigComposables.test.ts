import { createApp, ref, type App, type Ref } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReviewRuleConfig, ReviewStrategyPolicy } from "@/types";

const api = vi.hoisted(() => ({
  fetchReviewRuleVersions: vi.fn(),
  fetchReviewRules: vi.fn(),
  fetchReviewStrategyVersions: vi.fn(),
  rollbackReviewRule: vi.fn(),
  rollbackReviewStrategy: vi.fn(),
  updateReviewRule: vi.fn(),
  updateReviewRuleStatus: vi.fn(),
  updateReviewStrategyEnforcement: vi.fn()
}));

const messages = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}));

vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));

import { useReviewPolicyHistory } from "./useReviewPolicyHistory";
import { useReviewRuleCatalog } from "./useReviewRuleCatalog";
import { useReviewRuleEditor } from "./useReviewRuleEditor";
import { useReviewStrategyGovernance } from "./useReviewStrategyGovernance";

describe("rule configuration composables", () => {
  let app: App<Element> | undefined;
  let host: HTMLDivElement | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    app?.unmount();
    host?.remove();
    app = undefined;
    host = undefined;
  });

  it("loads and filters the rule catalog without mixing view state into the page", async () => {
    api.fetchReviewRules.mockResolvedValueOnce({
      metrics: [{ label: "规则", value: "2", note: "内置", color: "blue" }],
      qualityGroups: [],
      rules: [
        reviewRule({ id: "RG-AUTH-001", name: "认证规则", severity: "high" }),
        reviewRule({
          id: "RG-YAML-001",
          name: "配置规则",
          severity: "medium",
          applicableLanguages: "YAML"
        })
      ],
      strategyPolicy: strategyPolicy()
    });
    const state = useReviewRuleCatalog();

    await state.loadRules();

    expect(state.rules.value).toHaveLength(2);
    expect(state.strategyPolicy.value?.snapshotId).toBe(9);
    state.severityFilter.value = "high";
    expect(state.filteredRules.value.map(rule => rule.id)).toEqual(["RG-AUTH-001"]);
    state.severityFilter.value = "";
    state.keyword.value = "yaml";
    expect(state.filteredRules.value.map(rule => rule.id)).toEqual(["RG-YAML-001"]);
  });

  it("refreshes a conflicting rule edit and adopts the latest policy version", async () => {
    const originalRule = reviewRule({ policyVersion: 3, name: "旧名称" });
    const rules = ref([originalRule]);
    const reloadRules = vi.fn(async () => {
      rules.value = [reviewRule({ policyVersion: 4, name: "并发更新后的名称" })];
    });
    api.updateReviewRule.mockRejectedValueOnce(new Error("策略版本冲突"));
    const state = useReviewRuleEditor({ canManage: ref(true), reloadRules, rules });
    state.openEditDialog(originalRule);
    state.ruleForm.name = "  本地编辑  ";

    await state.saveRule();

    expect(api.updateReviewRule).toHaveBeenCalledWith(
      "RG-AUTH-001",
      3,
      expect.objectContaining({ id: "RG-AUTH-001", name: "本地编辑" })
    );
    expect(reloadRules).toHaveBeenCalledOnce();
    expect(state.editingPolicyVersion.value).toBe(4);
    expect(state.ruleForm.name).toBe("并发更新后的名称");
    expect(messages.error).toHaveBeenCalledWith("策略版本冲突");
  });

  it("uses the active snapshot for strategy updates and resynchronizes after conflicts", async () => {
    const policy = ref<ReviewStrategyPolicy | null>(strategyPolicy({ snapshotId: 9, enforcementMode: "observe" }));
    const reloadRules = vi.fn(async () => {
      policy.value = strategyPolicy({ snapshotId: 10, enforcementMode: "comment" });
    });
    api.updateReviewStrategyEnforcement.mockRejectedValueOnce(new Error("快照版本冲突"));
    const state = useReviewStrategyGovernance({
      canManage: ref(true),
      reloadRules,
      strategyPolicy: policy
    });
    state.strategyTargetMode.value = "block";

    await state.saveStrategyEnforcement();

    expect(api.updateReviewStrategyEnforcement).toHaveBeenCalledWith({
      enforcementMode: "block",
      expectedSnapshotId: 9
    });
    expect(policy.value?.snapshotId).toBe(10);
    expect(state.strategyTargetMode.value).toBe("comment");
    expect(messages.error).toHaveBeenCalledWith("快照版本冲突");
  });

  it("uses current versions for rollbacks and reloads both history channels", async () => {
    const rules = ref([reviewRule({ policyVersion: 7 })]);
    const policy = ref<ReviewStrategyPolicy | null>(strategyPolicy({ snapshotId: 11 }));
    const reloadRules = vi.fn(async () => undefined);
    api.fetchReviewRuleVersions.mockResolvedValue({ items: [], hasMore: false });
    api.fetchReviewStrategyVersions.mockResolvedValue({ items: [], hasMore: false });
    api.rollbackReviewRule.mockResolvedValue(reviewRule({ policyVersion: 8 }));
    api.rollbackReviewStrategy.mockResolvedValue(strategyPolicy({ snapshotId: 12 }));
    const state = mountHistory({ rules, policy, reloadRules });

    await state.openRuleVersions(rules.value[0]!);
    await state.rollbackRuleVersion(4);
    await state.rollbackStrategyVersion(6);

    expect(api.rollbackReviewRule).toHaveBeenCalledWith("RG-AUTH-001", 4, 7);
    expect(api.rollbackReviewStrategy).toHaveBeenCalledWith(6, 11);
    expect(api.fetchReviewRuleVersions).toHaveBeenCalledTimes(2);
    expect(api.fetchReviewStrategyVersions).toHaveBeenCalledOnce();
    expect(reloadRules).toHaveBeenCalledTimes(2);
  });

  it("cancels an active history request when the owning page unmounts", async () => {
    const request = deferred<{ items: never[]; hasMore: boolean }>();
    let signal: AbortSignal | undefined;
    api.fetchReviewRuleVersions.mockImplementationOnce(
      (_id: string, _query: unknown, options: { signal?: AbortSignal }) => {
        signal = options.signal;
        return request.promise;
      }
    );
    const rules = ref([reviewRule()]);
    const state = mountHistory({
      rules,
      policy: ref(strategyPolicy()),
      reloadRules: vi.fn(async () => undefined)
    });
    const pending = state.openRuleVersions(rules.value[0]!);
    await Promise.resolve();

    expect(signal?.aborted).toBe(false);
    app?.unmount();
    app = undefined;
    expect(signal?.aborted).toBe(true);
    request.reject(new Error("已取消"));

    await expect(pending).resolves.toBeUndefined();
    expect(messages.error).not.toHaveBeenCalled();
  });

  const mountHistory = ({
    rules,
    policy,
    reloadRules
  }: {
    rules: Ref<ReviewRuleConfig[]>;
    policy: Ref<ReviewStrategyPolicy | null>;
    reloadRules: () => Promise<void>;
  }) => {
    let state!: ReturnType<typeof useReviewPolicyHistory>;
    host = document.createElement("div");
    document.body.appendChild(host);
    app = createApp({
      setup() {
        state = useReviewPolicyHistory({
          canManage: ref(true),
          reloadRules,
          rules,
          strategyPolicy: policy
        });
        return () => null;
      }
    });
    app.mount(host);
    return state;
  };
});

const qualityGate = {
  labeledSamples: 30,
  labeledHighRiskSamples: 30,
  precision: 95,
  falsePositiveRate: 5,
  anchorRate: 90,
  duplicateRate: 1,
  commentEligible: true,
  blockEligible: true,
  status: "PASS",
  blockers: []
};

const reviewRule = (overrides: Partial<ReviewRuleConfig> = {}): ReviewRuleConfig => ({
  id: "RG-AUTH-001",
  name: "认证规则",
  scope: "Java Patch",
  applicableLanguages: "Java",
  filePatterns: "*.java",
  severity: "high",
  status: "enabled",
  hitCount: 2,
  confidence: "90",
  updatedAt: "2026-08-10T00:00:00Z",
  description: "检查认证边界",
  positiveExample: "positive",
  falsePositiveGuidance: "guidance",
  enforcementMode: "comment",
  detectorVersion: "detector-v1",
  configVersion: 1,
  policyVersion: 3,
  qualityGate,
  ...overrides
});

const strategyPolicy = (overrides: Partial<ReviewStrategyPolicy> = {}): ReviewStrategyPolicy => ({
  snapshotId: 9,
  strategyVersion: 2,
  promptVersion: "prompt-v1",
  contextVersion: "context-v1",
  schemaVersion: "schema-v1",
  verifierVersion: "verifier-v1",
  aggregationVersion: "aggregation-v1",
  enforcementMode: "observe",
  replayVerified: true,
  active: true,
  changeType: "UPDATE",
  createdAt: "2026-08-10T00:00:00Z",
  qualityGate,
  ...overrides
});

const deferred = <T>() => {
  let reject!: (reason?: unknown) => void;
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
};
