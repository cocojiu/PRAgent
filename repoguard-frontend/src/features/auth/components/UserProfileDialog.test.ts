import { createApp, defineComponent, h, nextTick, ref, type App, type Ref } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import { clearAuthToken, saveAuthToken } from "@/api/client";
import type { CurrentUser } from "@/api/auth";
import Dialog from "./UserProfileDialog.vue";
/* eslint-disable vue/one-component-per-file -- UI stubs exercise the actual read-only dialog. */
const api = vi.hoisted(() => ({ getCurrentUser: vi.fn(), logout: vi.fn(), changePassword: vi.fn() }));
vi.mock("@/api/auth", () => api);
const user = (overrides: Partial<CurrentUser> = {}): CurrentUser => ({ id: 1, username: "admin", email: "admin@example.test", role: "ADMIN",
  status: "ACTIVE", timezone: "Asia/Shanghai", language: "zh-CN", lastLoginAt: "2026-10-10T00:00:00Z", ...overrides });
const deferred = () => { let resolve!: (value: CurrentUser) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<CurrentUser>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
let app: App | undefined; let host: HTMLDivElement; let visible: Ref<boolean>;
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
const button = (label: string) => [...host.querySelectorAll("button")].find(node => node.textContent === label)!;
const mount = async () => {
  visible = ref(true); app = createApp(defineComponent({ setup: () => () => h(Dialog, { modelValue: visible.value, "onUpdate:modelValue": value => { visible.value = value; } }) }));
  app.component("ElDialog", defineComponent({ props: { modelValue: Boolean }, setup: (props, { slots }) => () => props.modelValue ? h("section", { role: "dialog" }, [slots.default?.(), slots.footer?.()]) : null }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } }, setup: props => () => h("p", { role: "alert" }, props.title) }));
  app.component("ElButton", defineComponent({ props: { loading: Boolean }, setup: (props, { slots }) => () => h("button", { disabled: props.loading }, slots.default?.()) }));
  app.mount(host); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); clearActiveTenant(); saveAuthToken("test-access", false); currentUser.value = user(); api.getCurrentUser.mockResolvedValue(user());
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; clearActiveTenant(); clearAuthToken(); });
describe("read-only personal profile", () => {
  it("reads and displays own profile without exposing inputs or changing the authenticated role", async () => {
    api.getCurrentUser.mockResolvedValueOnce(user({ username: "fresh name", role: "REVIEWER", email: "fresh@example.test" })); await mount();
    expect(host.textContent).toContain("fresh name"); expect(host.textContent).toContain("fresh@example.test"); expect(host.textContent).toContain("审查员");
    expect(host.textContent).toContain("Asia/Shanghai"); expect(host.querySelector("input,textarea,select")).toBeNull();
    expect(currentUser.value?.role).toBe("ADMIN"); expect(api.getCurrentUser.mock.calls[0]![0].signal).toBeInstanceOf(AbortSignal);
    expect(api.changePassword).not.toHaveBeenCalled(); expect(api.logout).not.toHaveBeenCalled();
  });
  it("keeps previous own details unconfirmed on errors and retries through the refresh button", async () => {
    await mount(); api.getCurrentUser.mockRejectedValueOnce(new Error("profile offline")); button("刷新资料").click(); await flush();
    expect(host.textContent).toContain("profile offline"); expect(host.textContent).toContain("当前状态尚未确认"); expect(host.textContent).toContain("admin@example.test");
    expect(currentUser.value?.id).toBe(1); button("刷新资料").click(); await flush(); expect(host.textContent).not.toContain("profile offline");
    expect(host.textContent).not.toContain("当前状态尚未确认"); expect(api.getCurrentUser).toHaveBeenCalledTimes(3);
  });
  it("rejects a profile for another account without showing it or changing the session", async () => {
    api.getCurrentUser.mockResolvedValueOnce(user({ id: 2, email: "other@example.test" })); await mount();
    expect(host.textContent).toContain("当前账户不一致"); expect(host.textContent).not.toContain("other@example.test"); expect(currentUser.value?.id).toBe(1);
  });
  it.each(["account", "tenant", "role", "logout"])("cancels and closes before a delayed response after %s", async change => {
    const d = deferred(); api.getCurrentUser.mockReturnValueOnce(d.promise); await mount(); const signal = api.getCurrentUser.mock.calls[0]![0].signal as AbortSignal;
    if (change === "account") currentUser.value = user({ id: 2 });
    if (change === "tenant") setActiveTenant("other");
    if (change === "role") currentUser.value = user({ role: "VIEWER" });
    if (change === "logout") currentUser.value = undefined;
    await flush(); expect(signal.aborted).toBe(true); expect(visible.value).toBe(false);
    d.resolve(user({ email: "old-result@example.test" })); await flush(); expect(host.textContent).not.toContain("old-result@example.test");
  });
  it("cancels immediately on close and ignores the old result when reopening", async () => {
    const d = deferred(); api.getCurrentUser.mockReturnValueOnce(d.promise); await mount(); const signal = api.getCurrentUser.mock.calls[0]![0].signal as AbortSignal;
    button("关闭").click(); await flush(); expect(signal.aborted).toBe(true); visible.value = true; await flush();
    d.resolve(user({ email: "old-result@example.test" })); await flush(); expect(host.textContent).not.toContain("old-result@example.test");
    expect(host.textContent).toContain("admin@example.test");
  });
  it("does not leave a late error after unmount", async () => {
    const d = deferred(); api.getCurrentUser.mockReturnValueOnce(d.promise); await mount(); const signal = api.getCurrentUser.mock.calls[0]![0].signal as AbortSignal;
    app!.unmount(); app = undefined; expect(signal.aborted).toBe(true); d.reject(new Error("late")); await flush(); expect(host.textContent).toBe("");
  });
  it("does not request a profile without an identified authenticated account", async () => {
    currentUser.value = undefined; clearAuthToken(); await mount(); expect(api.getCurrentUser).not.toHaveBeenCalled(); expect(host.textContent).toContain("重新登录");
  });
});
