import { createApp, computed, defineComponent, h, inject, nextTick, provide, type App, type Ref } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import type { NotificationBinding, NotificationBindingRequest } from "@/types";
import Manager from "./NotificationBindingManager.vue";

/* eslint-disable vue/one-component-per-file -- UI stubs preserve the real manager, table, editor and composable operation wiring. */
const api = vi.hoisted(() => ({ fetchNotificationBindings: vi.fn(), createNotificationBinding: vi.fn(), deleteNotificationBinding: vi.fn(),
  testNotificationBinding: vi.fn(), updateNotificationBinding: vi.fn(), updateNotificationBindingStatus: vi.fn() }));
vi.mock("@/api/config", () => api);
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() } }));
let app: App | undefined; let host: HTMLDivElement;
const row = (): NotificationBinding => ({ id: 1, name: "binding-1", provider: "DINGTALK", organization: "org", repository: "repo", enabled: true,
  notifyReviewCompleted: true, notifyReviewFailed: true, notifyHumanReviewRequired: true, notifyGithubComment: true, status: "CONFIGURED" });
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve }; };
const button = (text: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === text)!;
const editName = async (value: string) => { const input = host.querySelector<HTMLInputElement>('[role="dialog"] input')!; input.value = value; input.dispatchEvent(new Event("input")); await flush(); };
const mount = async () => {
  app = createApp(Manager); app.directive("loading", {});
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } }, setup: props => () => h("div", { role: "alert" }, props.title) }));
  app.component("ElDialog", defineComponent({ props: { modelValue: Boolean }, setup: (props, { slots }) => () => props.modelValue
    ? h("section", { role: "dialog" }, [slots.default?.(), slots.footer?.()]) : null }));
  app.component("ElInput", defineComponent({ props: { modelValue: { type: String, default: "" }, type: { type: String, default: "text" } }, emits: ["update:modelValue"],
    setup: (props, { emit }) => () => h("input", { value: props.modelValue, type: props.type,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) }));
  app.component("ElTable", defineComponent({ props: { data: { type: Array, default: () => [] } }, setup(props, { slots }) {
    provide("binding-row", computed(() => props.data[0])); return () => h("div", slots.default?.());
  } }));
  app.component("ElTableColumn", defineComponent({ setup(_, { slots }) {
    const selected = inject<Ref<NotificationBinding | undefined>>("binding-row")!;
    return () => selected.value ? h("div", slots.default?.({ row: selected.value })) : null;
  } }));
  for (const name of ["ElForm", "ElFormItem", "ElSelect", "ElTag", "ElCheckbox"]) app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  for (const name of ["ElOption", "ElSwitch", "ElPagination"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotificationBindings.mockResolvedValue({ items: [row()], total: 1 });
  api.updateNotificationBinding.mockImplementation((id: number, payload: NotificationBindingRequest) => Promise.resolve({ ...row(), ...payload, id }));
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; });

describe("notification binding manager UI", () => {
  it.each(["toggle", "delete"])("disables conflicting row actions while %s is pending", async operation => {
    await mount(); const pending = deferred<NotificationBinding>();
    (operation === "toggle" ? api.updateNotificationBindingStatus : api.deleteNotificationBinding).mockReturnValueOnce(pending.promise);
    button(operation === "toggle" ? "停用" : "删除").click(); await flush();
    for (const text of ["编辑", "测试", "停用", "删除"]) expect(button(text).disabled).toBe(true);
    pending.resolve({ ...row(), enabled: false }); await flush();
    expect(button("编辑").disabled).toBe(false);
  });

  it("shows failed query state and reloads before enabling row actions", async () => {
    api.fetchNotificationBindings.mockRejectedValueOnce(new Error("bindings offline")); await mount();
    expect(host.textContent).toContain("bindings offline"); button("刷新渠道").click(); await flush();
    expect(host.textContent).not.toContain("bindings offline"); expect(button("编辑").disabled).toBe(false);
  });

  it("keeps a newer name while saving and exposes a second explicit save", async () => {
    await mount(); button("编辑").click(); await flush(); expect(button("保存").disabled).toBe(true); await editName("submitted");
    const pending = deferred<NotificationBinding>(); api.updateNotificationBinding.mockReturnValueOnce(pending.promise); button("保存").click(); await flush();
    expect(button("保存").disabled).toBe(true); expect(host.textContent).toContain("等待期间的新编辑会保留"); await editName("later");
    pending.resolve({ ...row(), name: "submitted" }); await flush();
    expect(host.querySelector<HTMLInputElement>('[role="dialog"] input')!.value).toBe("later"); expect(host.textContent).toContain("有未保存的修改");
    expect(button("保存").disabled).toBe(false); button("保存").click(); await flush();
    expect(api.updateNotificationBinding).toHaveBeenLastCalledWith(1, expect.objectContaining({ name: "later" })); expect(host.querySelector('[role="dialog"]')).toBeNull();
  });

  it("keeps another editor open when the earlier save finishes", async () => {
    await mount(); button("编辑").click(); await flush(); await editName("submitted");
    const pending = deferred<NotificationBinding>(); api.updateNotificationBinding.mockReturnValueOnce(pending.promise); button("保存").click(); await flush();
    button("取消").click(); button("新增绑定").click(); await flush(); await editName("another editor"); pending.resolve({ ...row(), name: "submitted" }); await flush();
    expect(host.querySelector<HTMLInputElement>('[role="dialog"] input')!.value).toBe("another editor"); expect(button("保存").disabled).toBe(false);
  });

  it("keeps the editable draft and a visible save failure for manual retry", async () => {
    await mount(); button("编辑").click(); await flush(); await editName("submitted"); api.updateNotificationBinding.mockRejectedValueOnce(new Error("write offline"));
    button("保存").click(); await flush(); expect(host.textContent).toContain("write offline"); expect(button("保存").disabled).toBe(false);
    button("保存").click(); await flush(); expect(api.updateNotificationBinding).toHaveBeenCalledTimes(2); expect(host.querySelector('[role="dialog"]')).toBeNull();
  });
});
