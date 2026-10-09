import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import type { SystemSettingsRequest } from "@/types";
import Page from "./NotificationOpsPage.vue";

/* eslint-disable vue/one-component-per-file -- Stubs preserve real page/composable wiring for notification drafts, errors and list freshness. */
const api = vi.hoisted(() => ({ settings: vi.fn(), update: vi.fn(), events: vi.fn(), deliveries: vi.fn(), bindings: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
vi.mock("@/api/config", () => ({
  fetchSystemSettings: api.settings, updateSystemSettings: api.update, fetchNotificationEvents: api.events, fetchNotificationDeliveries: api.deliveries,
  fetchNotificationBindings: api.bindings, retryNotificationEvent: vi.fn(), createNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(),
  updateNotificationBindingStatus: vi.fn(), deleteNotificationBinding: vi.fn(), testNotificationBinding: vi.fn()
}));
let app: App | undefined; let host: HTMLDivElement;
const settings = () => ({ base: { systemName: "RepoGuard" }, policy: { autoComment: false }, security: { secretMasking: true },
  notification: { githubComment: true, failedTask: true, highRiskPr: true, email: "test@example.test" }, logs: [] });
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
const button = (text: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!;
const switches = () => [...host.querySelectorAll<HTMLInputElement>('.notification-settings-card input[type="checkbox"]')];
const deferred = <T>() => {
  let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve };
};
const mount = async () => {
  app = createApp(Page); app.directive("loading", {});
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElSwitch", defineComponent({ props: { modelValue: Boolean, disabled: Boolean }, emits: ["update:modelValue", "change"],
    setup: (props, { emit }) => () => h("input", { type: "checkbox", checked: props.modelValue, disabled: props.disabled,
      onChange: (event: Event) => { const value = (event.target as HTMLInputElement).checked; emit("update:modelValue", value); emit("change", value); } }) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } }, setup: props => () => h("div", { role: "alert" }, props.title) }));
  app.component("ElSelect", defineComponent({ props: { modelValue: { type: [String, Number], default: undefined } }, emits: ["update:modelValue"],
    setup: (props, { attrs, slots, emit }) => () => h("select", { ...attrs, value: props.modelValue,
      onChange: (event: Event) => emit("update:modelValue", (event.target as HTMLSelectElement).value) }, slots.default?.()) }));
  app.component("ElOption", defineComponent({ props: { value: { type: [String, Number], default: "" }, label: { type: String, default: "" } },
    setup: props => () => h("option", { value: props.value }, props.label) }));
  app.component("ElTable", defineComponent({ props: { data: { type: Array, default: () => [] } }, setup: (props, { attrs }) => () => h("div", attrs, JSON.stringify(props.data)) }));
  for (const name of ["ElTabs", "ElTabPane", "ElForm", "ElFormItem"]) app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  for (const name of ["ElTableColumn", "ElDialog", "ElInput", "ElInputNumber", "ElEmpty", "ElPagination", "ElCheckbox", "ElTag"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.settings.mockResolvedValue(settings()); api.update.mockImplementation((payload: SystemSettingsRequest) => Promise.resolve({ ...settings(), notification: payload.notification }));
  api.events.mockResolvedValue({ items: [{ id: 1, status: "FAILED", taskId: 7 }], total: 25 });
  api.deliveries.mockResolvedValue({ items: [], total: 0 }); api.bindings.mockResolvedValue({ items: [], total: 0 });
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; });

describe("notification operations page", () => {
  it("shows a current binding query failure and offers a cancellable refresh", async () => {
    api.bindings.mockRejectedValueOnce(new Error("bindings offline")); await mount();
    expect(host.textContent).toContain("bindings offline"); expect(host.textContent).toContain("当前渠道列表尚未确认最新状态");
    expect(button("测试发送").disabled).toBe(true); button("刷新渠道").click(); await flush();
    expect(host.textContent).not.toContain("bindings offline"); expect(api.bindings).toHaveBeenCalledTimes(2);
    expect(api.bindings.mock.calls[1]![1].signal).toBeInstanceOf(AbortSignal);
  });

  it("retains a switch edited during an automatic save and offers a manual save of the new draft", async () => {
    await mount(); const pending = deferred<ReturnType<typeof settings>>(); api.update.mockReturnValueOnce(pending.promise);
    switches()[0]!.click(); await flush(); switches()[2]!.click(); await flush(); expect(api.update).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain("等待期间的新编辑会保留");
    pending.resolve({ ...settings(), notification: { ...settings().notification, githubComment: false } }); await flush();
    expect(switches()[0]!.checked).toBe(false); expect(switches()[2]!.checked).toBe(false); expect(host.textContent).toContain("有未保存的修改");
    expect(button("保存触发策略").disabled).toBe(false); button("保存触发策略").click(); await flush();
    expect(api.update).toHaveBeenCalledTimes(2); expect(api.update.mock.calls[1]![0].notification.highRiskPr).toBe(false);
    expect(host.textContent).not.toContain("有未保存的修改"); expect(messages.success).toHaveBeenLastCalledWith("通知设置已保存");
  });

  it("shows save failure, retains the edited switch and allows manual retry", async () => {
    await mount(); api.update.mockRejectedValueOnce(new Error("save offline")); switches()[0]!.click(); await flush();
    expect(host.textContent).toContain("save offline"); expect(switches()[0]!.checked).toBe(false); expect(button("保存触发策略").disabled).toBe(false);
    button("保存触发策略").click(); await flush(); expect(api.update).toHaveBeenCalledTimes(2); expect(host.textContent).not.toContain("save offline");
  });

  it("offers a reload after the initial settings query fails without allowing a write", async () => {
    api.settings.mockRejectedValueOnce(new Error("initial read offline")); await mount(); expect(host.textContent).toContain("initial read offline");
    switches()[0]!.click(); await flush(); expect(api.update).not.toHaveBeenCalled(); expect(button("保存触发策略").disabled).toBe(true);
    button("重新读取通知设置").click(); await flush(); expect(host.textContent).not.toContain("initial read offline");
    expect(switches()[0]!.checked).toBe(false); expect(button("保存触发策略").disabled).toBe(false);
  });

  it("clears rows and totals when filters change and wires current read failure into the panel", async () => {
    await mount(); const filter = host.querySelector<HTMLSelectElement>('.notification-filter-bar select[placeholder="全部状态"]')!;
    const panel = filter.closest("article")!; filter.value = "PUBLISHED"; filter.dispatchEvent(new Event("change")); await flush();
    expect(panel.textContent).toContain("当前筛选尚未完成查询"); expect(panel.querySelector(".rg-table")!.textContent).toBe("[]");
    expect(host.querySelector(".table-footer")!.textContent).toContain("共 0 条");
    api.events.mockRejectedValueOnce(new Error("query offline")); [...panel.querySelectorAll("button")].find(node => node.textContent?.includes("刷新"))!.click(); await flush();
    expect(panel.textContent).toContain("query offline"); expect(api.events.mock.calls[1]![0].status).toBe("PUBLISHED");
    expect(api.events.mock.calls[1]![1].signal).toBeInstanceOf(AbortSignal);
  });
});
