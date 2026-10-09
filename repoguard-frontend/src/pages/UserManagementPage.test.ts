import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import UserManagementPage from "./UserManagementPage.vue";

/* eslint-disable vue/one-component-per-file -- Component stubs exercise the real page's form and request lifecycle. */

const api = vi.hoisted(() => ({ users: vi.fn(), audits: vi.fn(), create: vi.fn() }));
const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("@/api/users", () => ({
  fetchUsers: api.users, fetchUserOperationAudits: api.audits, createUser: api.create,
  updateUserRole: vi.fn(), updateUserStatus: vi.fn()
}));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
vi.mock("@/components/MetricGrid.vue", () => ({ default: { render: () => null } }));

let app: App | null;
let host: HTMLDivElement;
const emptyPage = { items: [], total: 0 };
const flush = async () => {
  for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); }
};
const button = (label: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === label)!;
const input = (placeholder: string) => host.querySelector<HTMLInputElement>(`input[placeholder="${placeholder}"]`)!;
const fillCreateForm = async () => {
  button("创建用户").click(); await nextTick();
  for (const [placeholder, value] of [
    ["请输入用户名", "new-user"], ["请输入企业邮箱", "new@example.test"],
    ["至少 8 位，包含字母和数字", "fixture-password-123"], ["再次输入初始密码", "fixture-password-123"]
  ]) {
    const field = input(placeholder!); field.value = value!; field.dispatchEvent(new Event("input"));
  }
  await nextTick();
};

beforeEach(async () => {
  vi.resetAllMocks();
  api.users.mockResolvedValue(emptyPage); api.audits.mockResolvedValue(emptyPage);
  api.create.mockResolvedValue({ id: 8, username: "new-user" });
  currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  host = document.createElement("div"); document.body.append(host);
  app = createApp(UserManagementPage);
  app.directive("loading", () => {});
  app.component("ElInput", defineComponent({
    props: { modelValue: { type: String, default: "" } }, emits: ["update:modelValue"],
    setup: (props, { attrs, emit }) => () => h("input", { ...attrs, value: props.modelValue,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) })
  }));
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElDialog", defineComponent({ props: { modelValue: Boolean },
    setup: (props, { slots }) => () => props.modelValue ? h("div", { role: "dialog" }, [slots.default?.(), slots.footer?.()]) : null }));
  for (const name of ["ElForm", "ElFormItem"]) {
    app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  }
  for (const name of ["ElSelect", "ElOption", "ElTable", "ElTableColumn", "ElEmpty", "ElPagination"]) {
    app.component(name, defineComponent({ setup: () => () => null }));
  }
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = null; host.remove(); currentUser.value = undefined; });

it("confirms creation and closes the form even when the following user-list refresh fails", async () => {
  await fillCreateForm();
  api.users.mockRejectedValueOnce(new Error("list unavailable"));
  button("创建").click(); await flush();
  expect(api.create).toHaveBeenCalledTimes(1);
  expect(messages.success).toHaveBeenCalledWith("用户已创建");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  expect(messages.error).not.toHaveBeenCalledWith("用户创建失败");
  expect(messages.error).toHaveBeenCalledWith("list unavailable");
  button("创建用户").click(); await nextTick();
  expect(input("请输入用户名").value).toBe("");
  expect(input("至少 8 位，包含字母和数字").value).toBe("");
});

it("retains the form and allows a manual retry only when the creation request fails", async () => {
  await fillCreateForm();
  api.create.mockRejectedValueOnce(new Error("create unavailable"));
  button("创建").click(); await flush();
  expect(messages.success).not.toHaveBeenCalled();
  expect(messages.error).toHaveBeenCalledWith("create unavailable");
  expect(input("请输入用户名").value).toBe("new-user");
  expect(api.users).toHaveBeenCalledTimes(1);
  button("创建").click(); await flush();
  expect(api.create).toHaveBeenCalledTimes(2);
  expect(messages.success).toHaveBeenCalledWith("用户已创建");
});

it("does not submit a second creation while the first request is pending", async () => {
  await fillCreateForm();
  let finish!: (value: unknown) => void;
  api.create.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const submit = button("创建"); submit.click(); submit.click(); await nextTick();
  expect(api.create).toHaveBeenCalledTimes(1);
  finish({ id: 8 }); await flush();
  expect(messages.success).toHaveBeenCalledWith("用户已创建");
});

it("keeps a confirmed creation when the audit refresh fails", async () => {
  await fillCreateForm();
  api.audits.mockRejectedValueOnce(new Error("audit unavailable"));
  button("创建").click(); await flush();
  expect(messages.success).toHaveBeenCalledWith("用户已创建");
  expect(messages.error).toHaveBeenCalledWith("audit unavailable");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});
