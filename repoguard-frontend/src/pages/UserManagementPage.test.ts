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
  app.directive("loading", {
    mounted: (element, binding) => { element.dataset.loading = String(binding.value); },
    updated: (element, binding) => { element.dataset.loading = String(binding.value); }
  });
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
  app.component("ElTable", defineComponent({ props: { data: { type: Array, default: () => [] } },
    setup: props => () => h("div", JSON.stringify(props.data)) }));
  app.component("ElPagination", defineComponent({ props: { currentPage: { type: Number, default: 1 } },
    emits: ["current-change"], setup: (props, { emit }) => () =>
      h("button", { onClick: () => emit("current-change", props.currentPage + 1) }, "下一页") }));
  for (const name of ["ElSelect", "ElOption", "ElTableColumn", "ElEmpty"]) {
    app.component(name, defineComponent({ setup: () => () => null }));
  }
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = null; host.remove(); currentUser.value = undefined; vi.useRealTimers(); });

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

it("aborts the stale user read as soon as search changes and waits for the debounce before querying again", async () => {
  vi.useFakeTimers();
  const old = deferred<typeof emptyPage>();
  api.users.mockReturnValueOnce(old.promise);
  button("刷新").click(); await flush();
  const signal = api.users.mock.calls[1]![1].signal as AbortSignal;
  const field = input("搜索用户名或邮箱"); field.value = "new"; field.dispatchEvent(new Event("input"));
  await flush();
  expect(signal.aborted).toBe(true); expect(api.users).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(350); await flush();
  expect(api.users).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: "new", page: 1 }), { signal: expect.any(AbortSignal) });
  old.reject(new Error("stale user error")); await flush();
  expect(messages.error).not.toHaveBeenCalled();
});

it("keeps audit loading for a newer page when an older cancelled read fails", async () => {
  const old = deferred<typeof emptyPage>(); const latest = deferred<typeof emptyPage>();
  api.audits.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  button("刷新记录").click(); await flush();
  [...host.querySelectorAll("button")].filter(node => node.textContent === "下一页")[1]!.click(); await flush();
  expect(api.audits.mock.calls[1]![1].signal.aborted).toBe(true);
  old.reject(new Error("stale audit error")); await flush();
  expect(host.querySelector<HTMLElement>('[aria-label="用户操作审计列表"]')!.dataset.loading).toBe("true");
  expect(messages.error).not.toHaveBeenCalled();
  latest.resolve(emptyPage); await flush();
  expect(host.querySelector<HTMLElement>('[aria-label="用户操作审计列表"]')!.dataset.loading).toBe("false");
});

it("aborts both reads on unmount and does not show stale errors", async () => {
  const users = deferred<typeof emptyPage>(); const audits = deferred<typeof emptyPage>();
  api.users.mockReturnValueOnce(users.promise); api.audits.mockReturnValueOnce(audits.promise);
  button("刷新").click(); await flush();
  const signals = [api.users.mock.calls[1]![1].signal, api.audits.mock.calls[1]![1].signal];
  app!.unmount(); app = null;
  expect(signals.every((signal: AbortSignal) => signal.aborted)).toBe(true);
  users.reject(new Error("unmounted user read")); audits.reject(new Error("unmounted audit read")); await flush();
  expect(messages.error).not.toHaveBeenCalled();
});

it("shows a user-list pagination failure and releases its loading state", async () => {
  api.users.mockRejectedValueOnce(new Error("page unavailable"));
  button("下一页").click(); await flush();
  expect(messages.error).toHaveBeenCalledWith("page unavailable");
  expect(host.querySelector<HTMLElement>('[aria-label="用户管理列表"]')!.dataset.loading).toBe("false");
});

const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
