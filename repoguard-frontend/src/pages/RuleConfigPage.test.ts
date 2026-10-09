import { createApp, defineComponent, h, nextTick, type App, type PropType, type Slot } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import type { ReviewRuleConfig, ReviewRuleConfigRequest, ReviewRulesResponse, ReviewStrategyPolicy } from "@/types";
import Page from "./RuleConfigPage.vue";

/* eslint-disable vue/one-component-per-file -- UI stubs exercise the real rule catalog, editor and strategy composables together. */
const api = vi.hoisted(() => ({ read: vi.fn(), create: vi.fn(), update: vi.fn(), status: vi.fn(), strategy: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
vi.mock("@/api/config", () => ({
  fetchReviewRules: api.read, createReviewRule: api.create, updateReviewRule: api.update,
  updateReviewRuleStatus: api.status, updateReviewStrategyEnforcement: api.strategy,
  fetchReviewRuleVersions: vi.fn(), fetchReviewStrategyVersions: vi.fn(), rollbackReviewRule: vi.fn(), rollbackReviewStrategy: vi.fn()
}));
vi.mock("@/features/rule-config/components/RepositoryPolicyPanel.vue", () => ({ default: { render: () => null } }));
vi.mock("@/features/rule-config/components/ReviewCalibrationQueueCard.vue", () => ({ default: { render: () => null } }));
vi.mock("@/features/rule-config/components/LlmEvaluationWorkbench.vue", () => ({ default: { render: () => null } }));
vi.mock("@/features/rule-config/components/LlmModelReleaseCenter.vue", () => ({ default: { render: () => null } }));
const gate = () => ({ labeledSamples: 0, labeledHighRiskSamples: 0, precision: 0, falsePositiveRate: 0, anchorRate: 0,
  duplicateRate: 0, commentEligible: false, blockEligible: false, status: "INSUFFICIENT_SAMPLE", blockers: ["missing labels"] });
const rule = (overrides: Partial<ReviewRuleConfig> = {}): ReviewRuleConfig => ({
  id: "RG-A", name: "original", scope: "Java", applicableLanguages: "Java", filePatterns: "*.java", severity: "high", status: "enabled",
  hitCount: 2, confidence: "90", updatedAt: "2026-10-01T00:00:00Z", description: "description", positiveExample: "example",
  falsePositiveGuidance: "guidance", enforcementMode: "observe", detectorVersion: "detector-v1", detectorType: "REGEX",
  matcherExpression: "token:save", exceptionPatterns: "", configVersion: 1, policyVersion: 3, qualityGate: gate(), ...overrides
});
const policy = (overrides: Partial<ReviewStrategyPolicy> = {}): ReviewStrategyPolicy => ({
  snapshotId: 9, strategyVersion: 2, promptVersion: "prompt-v1", contextVersion: "context-v1", schemaVersion: "schema-v1",
  verifierVersion: "verifier-v1", aggregationVersion: "aggregation-v1", enforcementMode: "observe", replayVerified: true,
  active: true, changeType: "UPDATE", createdAt: "2026-10-01T00:00:00Z", qualityGate: gate(), ...overrides
});
let app: App | undefined; let host: HTMLDivElement; let server: ReviewRulesResponse;
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } };
const deferred = <T>() => {
  let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve };
};
const button = (text: string, parent: ParentNode = host) => [...parent.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!;
const row = (id: string) => host.querySelector(`[aria-label="规则配置列表"] [data-rule-id="${id}"]`)!;
const editor = () => host.querySelector('[role="dialog"][aria-label="编辑规则"]');
const nameInput = () => editor()!.querySelector<HTMLInputElement>('input[placeholder="请输入规则名称"]')!;
const modeSelect = () => host.querySelector<HTMLSelectElement>('select[aria-label="策略处置模式"]')!;
const input = async (element: HTMLInputElement | HTMLSelectElement, value: string) => {
  element.value = value; element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? "change" : "input")); await flush();
};
const persist = (saved: ReviewRuleConfig) => { server.rules = server.rules.map(item => item.id === saved.id ? saved : item); return saved; };
const mount = async () => {
  app = createApp(Page); app.directive("loading", {});
  app.component("ElButton", defineComponent({ props: { disabled: Boolean, loading: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.disabled || props.loading }, slots.default?.()) }));
  app.component("ElInput", defineComponent({ props: { modelValue: { type: String, default: "" }, disabled: Boolean }, emits: ["update:modelValue"],
    setup: (props, { attrs, emit }) => () => h("input", { ...attrs, value: props.modelValue, disabled: props.disabled,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) }));
  app.component("ElSelect", defineComponent({ props: { modelValue: { type: String, default: "" }, disabled: Boolean }, emits: ["update:modelValue"],
    setup: (props, { attrs, slots, emit }) => () => h("select", { ...attrs, value: props.modelValue, disabled: props.disabled,
      onChange: (event: Event) => emit("update:modelValue", (event.target as HTMLSelectElement).value) }, slots.default?.()) }));
  app.component("ElOption", defineComponent({ props: { value: { type: String, default: "" }, label: { type: String, default: "" } },
    setup: props => () => h("option", { value: props.value }, props.label) }));
  app.component("ElSwitch", defineComponent({ props: { modelValue: { type: String, default: "disabled" }, disabled: Boolean, loading: Boolean }, emits: ["change", "update:modelValue"],
    setup: (props, { emit }) => () => h("input", { type: "checkbox", checked: props.modelValue === "enabled", disabled: props.disabled || props.loading,
      onChange: (event: Event) => {
        const element = event.target as HTMLInputElement; const value = element.checked ? "enabled" : "disabled";
        emit("update:modelValue", value); emit("change", value); element.checked = props.modelValue === "enabled";
      } }) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } }, setup: props => () => h("div", { role: "alert" }, props.title) }));
  app.component("ElDialog", defineComponent({ props: { modelValue: Boolean, title: { type: String, default: "" } },
    setup: (props, { slots }) => () => props.modelValue ? h("section", { role: "dialog", "aria-label": props.title }, [slots.default?.(), slots.footer?.()]) : null }));
  app.component("ElTable", defineComponent({ props: { data: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
    setup: (props, { attrs, slots }) => () => h("div", attrs, props.data.map((item, index) => h("article", { "data-rule-id": item.id ?? index },
      slots.default?.().map(column => {
        const cell = (column.children as { default?: Slot } | null)?.default;
        return cell?.({ row: item }) ?? String(item[String(column.props?.prop)] ?? "");
      })))) }));
  for (const name of ["ElForm", "ElFormItem", "ElTag", "ElTooltip"]) app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  for (const name of ["ElTableColumn", "ElInputNumber", "ElEmpty"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  server = { rules: [rule(), rule({ id: "RG-B", name: "second" })], metrics: [], qualityGroups: [], strategyPolicy: policy() };
  api.read.mockImplementation(async () => ({ ...server, rules: [...server.rules], strategyPolicy: { ...server.strategyPolicy! } }));
  api.update.mockImplementation(async (id: string, version: number, payload: ReviewRuleConfigRequest) =>
    persist(rule({ ...payload, id, confidence: String(payload.confidence), policyVersion: version + 1 })));
  api.strategy.mockImplementation(async ({ enforcementMode, expectedSnapshotId }) => {
    server.strategyPolicy = policy({ enforcementMode, snapshotId: expectedSnapshotId + 1 }); return server.strategyPolicy;
  });
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; });

describe("rule configuration page operation wiring", () => {
  it("marks retained rows unconfirmed and disables writes until an explicit successful refresh", async () => {
    await mount(); await input(modeSelect(), "comment"); api.read.mockRejectedValueOnce(new Error("catalog offline"));
    button("刷新规则列表").click(); await flush();
    expect(host.textContent).toContain("catalog offline"); expect(host.textContent).toContain("上次读取的规则与策略");
    expect(row("RG-A").textContent).toContain("original"); expect(button("编辑", row("RG-A")).disabled).toBe(true);
    expect(button("新增声明式规则").disabled).toBe(true); expect(button("应用模式").disabled).toBe(true);
    button("刷新规则列表").click(); await flush(); expect(host.textContent).not.toContain("catalog offline");
    expect(button("编辑", row("RG-A")).disabled).toBe(false); expect(button("应用模式").disabled).toBe(false);
    expect(modeSelect().value).toBe("comment"); expect(api.read.mock.calls[2]![0].signal).toBeInstanceOf(AbortSignal);
  });

  it("retains edits made during a save and only submits them after a second manual save", async () => {
    await mount(); button("编辑", row("RG-A")).click(); await flush(); await input(nameInput(), "submitted");
    const write = deferred<ReviewRuleConfig>(); api.update.mockReturnValueOnce(write.promise); button("保存").click(); await flush();
    expect(button("保存").disabled).toBe(true); expect(button("编辑", row("RG-A")).disabled).toBe(true);
    expect(button("编辑", row("RG-B")).disabled).toBe(false); expect(row("RG-A").querySelector<HTMLInputElement>("input")!.disabled).toBe(true);
    await input(nameInput(), "later draft"); write.resolve(persist(rule({ name: "submitted", policyVersion: 4 }))); await flush();
    expect(editor()).not.toBeNull(); expect(nameInput().value).toBe("later draft"); expect(button("保存").disabled).toBe(false);
    expect(api.update).toHaveBeenCalledTimes(1); button("保存").click(); await flush();
    expect(api.update.mock.calls[1]!.slice(0, 2)).toEqual(["RG-A", 4]); expect(api.update.mock.calls[1]![2].name).toBe("later draft");
    expect(editor()).toBeNull();
  });

  it("retains a conflicted draft and only adopts the new version through the explicit refresh control", async () => {
    await mount(); button("编辑", row("RG-A")).click(); await flush(); await input(nameInput(), "my draft");
    persist(rule({ name: "server changed", policyVersion: 4 })); api.update.mockRejectedValueOnce(new Error("version conflict"));
    button("保存").click(); await flush(); expect(editor()!.textContent).toContain("version conflict");
    expect(editor()!.textContent).toContain("规则版本已变化"); expect(nameInput().value).toBe("my draft"); expect(button("保存").disabled).toBe(true);
    button("刷新版本并保留草稿").click(); await flush(); expect(nameInput().value).toBe("my draft");
    expect(button("保存").disabled).toBe(false); expect(api.update).toHaveBeenCalledTimes(1);
    button("保存").click(); await flush(); expect(api.update.mock.calls[1]![1]).toBe(4);
  });

  it("locks same-rule edits and saves during status changes while displaying the confirmed status", async () => {
    await mount(); button("编辑", row("RG-A")).click(); await flush(); await input(nameInput(), "my draft");
    const write = deferred<ReviewRuleConfig>(); api.status.mockReturnValueOnce(write.promise);
    row("RG-A").querySelector<HTMLInputElement>("input")!.click(); await flush();
    expect(row("RG-A").querySelector<HTMLInputElement>("input")!.checked).toBe(true);
    expect(button("编辑", row("RG-A")).disabled).toBe(true); expect(button("编辑", row("RG-B")).disabled).toBe(false);
    expect(button("保存").disabled).toBe(true); expect(button("刷新版本并保留草稿").disabled).toBe(true);
    write.resolve(persist(rule({ status: "disabled", policyVersion: 4 }))); await flush();
    expect(row("RG-A").querySelector<HTMLInputElement>("input")!.checked).toBe(false); expect(nameInput().value).toBe("my draft");
    expect(api.update).not.toHaveBeenCalled(); expect(api.status).toHaveBeenCalledWith("RG-A", { status: "disabled", expectedPolicyVersion: 3 });
  });

  it.each(["block", "observe"])("keeps the later %s choice after an acknowledged strategy save and requires manual application", async later => {
    await mount(); await input(modeSelect(), "comment"); const write = deferred<ReviewStrategyPolicy>(); api.strategy.mockReturnValueOnce(write.promise);
    button("应用模式").click(); await flush(); await input(modeSelect(), later);
    expect(button("应用模式").disabled).toBe(true); expect(modeSelect().disabled).toBe(false); expect(api.strategy).toHaveBeenCalledTimes(1);
    server.strategyPolicy = policy({ snapshotId: 10, enforcementMode: "comment" }); write.resolve(server.strategyPolicy); await flush();
    expect(modeSelect().value).toBe(later); expect(host.textContent).toContain("新的选择尚未保存"); expect(button("应用模式").disabled).toBe(false);
    button("应用模式").click(); await flush(); expect(api.strategy.mock.calls[1]![0]).toEqual({ enforcementMode: later, expectedSnapshotId: 10 });
  });

  it("shows a rejected strategy promotion without discarding the draft or relaxing displayed quality gates", async () => {
    await mount(); await input(modeSelect(), "comment"); api.strategy.mockRejectedValueOnce(new Error("missing explicit labels"));
    button("应用模式").click(); await flush(); expect(host.textContent).toContain("missing explicit labels"); expect(modeSelect().value).toBe("comment");
    expect(host.textContent).toContain("待明确标注"); expect(host.textContent).toContain("需至少 1 条"); expect(host.textContent).toContain("少于 30 个样本");
    expect(host.textContent).toContain("INSUFFICIENT_SAMPLE"); expect(server.strategyPolicy!.enforcementMode).toBe("observe");
    expect(api.strategy).toHaveBeenCalledTimes(1); expect(messages.success).not.toHaveBeenCalled();
  });

  it("clears prior account rows and editor and ignores its delayed strategy response", async () => {
    await mount(); button("编辑", row("RG-A")).click(); await flush(); await input(nameInput(), "old draft");
    await input(modeSelect(), "comment"); const write = deferred<ReviewStrategyPolicy>(); api.strategy.mockReturnValueOnce(write.promise);
    button("应用模式").click(); await flush(); currentUser.value = { ...currentUser.value!, id: 2 }; await flush();
    expect(editor()).toBeNull(); expect(host.querySelector('[data-rule-id="RG-A"]')).toBeNull(); expect(modeSelect()).toBeNull();
    const reads = api.read.mock.calls.length; write.resolve(policy({ snapshotId: 10, enforcementMode: "comment" })); await flush();
    expect(api.read).toHaveBeenCalledTimes(reads); expect(messages.success).not.toHaveBeenCalled(); expect(host.textContent).not.toContain("old draft");
  });
});
