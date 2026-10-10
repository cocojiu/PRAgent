import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import ReleaseCenter from "./LlmModelReleaseCenter.vue";

/* eslint-disable vue/one-component-per-file -- UI stubs preserve the real release-center read states and action guards. */
const api = vi.hoisted(() => ({ center: vi.fn(), reports: vi.fn(), metrics: vi.fn(), audits: vi.fn() }));
vi.mock("@/api/config", () => ({
  fetchLlmModelReleaseCenter: api.center, fetchLlmEvaluationReports: api.reports,
  fetchLlmModelReleaseRuntimeMetrics: api.metrics, fetchLlmModelReleaseAudits: api.audits,
  exportLlmModelReleaseAudits: vi.fn(), verifyLlmModelReleaseAudit: vi.fn(), promoteLlmModelRelease: vi.fn(),
  registerLlmModelShadowRelease: vi.fn(), rollbackLlmModelRelease: vi.fn(), transitionLlmEvaluationReportLifecycle: vi.fn()
}));
let app: App | null;
let host: HTMLDivElement;
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
const button = (label: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === label)!;
const center = { configuredProvider: "fixture-provider", configuredModel: "fixture-model", releases: [], modelComparison: [],
  monthlyBudget: { tokenRemaining: 10, costRemaining: 10, exhausted: false }, recommendedAction: "RUN_SHADOW" };

beforeEach(() => {
  vi.resetAllMocks();
  api.center.mockResolvedValue(center); api.reports.mockResolvedValue([]);
  api.metrics.mockResolvedValue([]); api.audits.mockResolvedValue({ items: [], total: 0 });
  currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = null; host.remove(); currentUser.value = undefined; });
const mount = async () => {
  app = createApp(ReleaseCenter);
  app.component("ElAlert", defineComponent({ props: {
    title: { type: String, default: "" }, description: { type: String, default: "" }
  },
    setup: (props, { slots }) => () => h("div", { role: "status" }, [props.title, props.description, slots.default?.()]) }));
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  for (const name of ["ElCollapse", "ElCollapseItem", "ElForm", "ElFormItem", "ElTable"]) {
    app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  }
  for (const name of ["ElSelect", "ElOption", "ElInput", "ElInputNumber", "ElTableColumn", "ElEmpty", "ElPagination"]) {
    app.component(name, defineComponent({ setup: () => () => null }));
  }
  app.mount(host); await flush();
};

it("shows the successful release state and a scoped report warning after a partial failure", async () => {
  api.reports.mockRejectedValueOnce(new Error("reports unavailable"));
  await mount();
  expect(host.textContent).toContain("fixture-model");
  expect(host.textContent).toContain("评估报告：reports unavailable");
  expect(host.textContent).toContain("尚无成功查询结果");
  expect(button("注册 Shadow").disabled).toBe(true);
});

it("shows independent runtime and audit sections even if the initial center query fails", async () => {
  api.center.mockRejectedValueOnce(new Error("center unavailable"));
  await mount();
  expect(host.textContent).toContain("发布状态：center unavailable");
  expect(host.querySelector('[aria-label="模型发布运行指标"]')).not.toBeNull();
  expect(host.querySelector('[aria-label="模型发布审计时间线"]')).not.toBeNull();
  expect(host.querySelector('[aria-label="模型发布记录"]')).toBeNull();
});

it("marks retained data with its successful window and time after a refresh fails", async () => {
  await mount();
  api.center.mockRejectedValueOnce(new Error("refresh unavailable"));
  button("刷新发布状态").click(); await flush();
  expect(host.textContent).toContain("fixture-model");
  expect(host.textContent).toContain("发布状态：refresh unavailable");
  expect(host.textContent).toMatch(/当前显示上次成功数据：30 天窗口，\d{4}/);
});
