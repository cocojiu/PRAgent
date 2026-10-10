import { createApp, nextTick, type App } from "vue";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import { clearAuthToken, saveAuthToken } from "@/api/client";
import Layout from "./RepoGuardLayout.vue";
const api = vi.hoisted(() => ({ fetchNotifications: vi.fn(), fetchNotificationReadKeys: vi.fn(), markNotificationRead: vi.fn() }));
vi.mock("@/api/notifications", () => api);
vi.mock("@/config/edition", () => ({ enterpriseEditionEnabled: true }));
vi.mock("@/components/TenantSwitcher.vue", () => ({ __esModule: true, default: { render: () => null } }));
const notifications = { total: 2, generatedAt: "2026-10-10T00:00:00Z", items: ["n1", "n2"].map(id => ({ id, level: "warning", title: id,
  description: "review waiting", time: "now", targetPath: "/repoguard/tasks" })) };
let app: App | undefined; let host: HTMLDivElement; let router: Router;
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } };
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(yes => { resolve = yes; }); return { promise, resolve }; };
const button = (label: string) => [...host.querySelectorAll("button")].find(node => node.textContent?.trim() === label)!;
const open = async () => { host.querySelector<HTMLButtonElement>('button[aria-label="查看通知"]')!.click(); await flush(); };
const mount = async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/:pathMatch(.*)*", component: { render: () => null } }] });
  await router.push("/repoguard/overview"); await router.isReady(); app = createApp(Layout); app.use(router); app.mount(host); await flush();
};
beforeEach(() => {
  vi.resetAllMocks(); window.localStorage.clear(); saveAuthToken("test-access", false);
  currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetchNotifications.mockResolvedValue(notifications); api.fetchNotificationReadKeys.mockResolvedValue([]); api.markNotificationRead.mockResolvedValue(undefined);
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); clearAuthToken(); currentUser.value = undefined; });
describe("notification menu synchronization", () => {
  it("shows local-only state and a real manual retry after a failed server write", async () => {
    await mount(); await open(); api.markNotificationRead.mockRejectedValueOnce(new Error("sync unavailable"));
    host.querySelector<HTMLButtonElement>(".notification-item")!.click(); await flush(); await open();
    expect(host.textContent).toContain("本地已读，服务端未确认"); expect(host.textContent).toContain("sync unavailable");
    expect(router.currentRoute.value.path).toBe("/repoguard/tasks"); expect(api.markNotificationRead).toHaveBeenCalledOnce();
    button("重试已读同步").click(); await flush(); expect(host.textContent).not.toContain("sync unavailable");
    expect(host.textContent).not.toContain("服务端未确认"); expect(api.markNotificationRead).toHaveBeenCalledTimes(2);
  });
  it("disables bulk actions while waiting and only reports server confirmation after both writes", async () => {
    await mount(); await open(); const first = deferred(); const second = deferred(); api.markNotificationRead.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    button("全部已读").click(); await flush(); expect(button("全部已读").disabled).toBe(true); expect(host.textContent).not.toContain("已同步到服务端");
    expect(api.markNotificationRead).toHaveBeenCalledOnce(); first.resolve(); await flush(); expect(api.markNotificationRead).toHaveBeenCalledTimes(2);
    expect(button("全部已读").disabled).toBe(true); second.resolve(); await flush(); expect(host.textContent).toContain("当前通知的已读状态已同步到服务端");
  });
  it("clears the old account panel and suppresses delayed read failures", async () => {
    await mount(); await open(); currentUser.value = { ...currentUser.value!, id: 2 }; await flush();
    expect(host.querySelector(".notification-item")).toBeNull(); expect(host.textContent).not.toContain("review waiting");
    button("刷新通知与已读记录").click(); await flush(); expect(host.querySelectorAll(".notification-item")).toHaveLength(2);
  });
});
