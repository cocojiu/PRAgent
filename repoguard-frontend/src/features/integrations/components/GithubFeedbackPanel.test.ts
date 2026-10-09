import { computed, createApp, defineComponent, h, inject, nextTick, provide, type App, type PropType, type Ref } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentUser } from "@/stores/authState";
import type { GithubFeedbackDiagnostics } from "@/types";
import Panel from "./GithubFeedbackPanel.vue";

/* eslint-disable vue/one-component-per-file -- Table and button stubs exercise the real feedback panel across concurrent requests. */
const api = vi.hoisted(() => ({ fetch: vi.fn(), retry: vi.fn() }));
vi.mock("@/api/config", () => ({ fetchGithubFeedback: api.fetch, retryGithubFeedback: api.retry }));
let app: App | undefined;
let host: HTMLDivElement;
const flush = async () => { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); } };
const result = (status = "FAILED"): GithubFeedbackDiagnostics => ({ enabled: true,
  events: [1, 2].map(id => ({ id, deliveryId: `delivery-${id}`, taskId: 7, findingId: 9, feedbackStatus: "false_positive", status, attempts: 1, failureCode: null, updatedAt: "2026-10-09T12:00:00Z" })) });
const retryButtons = () => [...host.querySelectorAll("button")].filter(node => node.textContent === "重试");
const refreshButton = () => [...host.querySelectorAll("button")].find(node => node.textContent === "刷新反馈状态")!;
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
beforeEach(async () => {
  vi.resetAllMocks(); currentUser.value = { id: 1, username: "admin", email: "admin@example.test", role: "ADMIN", status: "ACTIVE" };
  api.fetch.mockResolvedValue(result()); api.retry.mockResolvedValue(undefined);
  host = document.createElement("div"); document.body.append(host); app = createApp(Panel);
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } }, setup: props => () => h("div", { role: "alert" }, props.title) }));
  app.component("ElTable", defineComponent({ props: { data: { type: Array as PropType<GithubFeedbackDiagnostics["events"]>, default: () => [] } },
    setup: (props, { slots }) => { provide("rows", computed(() => props.data)); return () => h("div", [JSON.stringify(props.data), slots.default?.()]); } }));
  app.component("ElTableColumn", defineComponent({ setup: (_, { slots }) => {
    const rows = inject<Ref<GithubFeedbackDiagnostics["events"]>>("rows")!;
    return () => h("div", slots.default ? rows.value.map(row => slots.default!({ row })) : []);
  } }));
  app.component("ElEmpty", defineComponent({ setup: () => () => null }));
  app.mount(host); await flush();
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); currentUser.value = undefined; });

describe("feedback retry panel", () => {
  it("disables every retry button while one event is being enqueued", async () => {
    const pending = deferred<void>(); api.retry.mockReturnValueOnce(pending.promise);
    retryButtons()[0]!.click(); await flush(); expect(retryButtons().every(node => node.disabled)).toBe(true);
    retryButtons()[1]!.click(); expect(api.retry).toHaveBeenCalledTimes(1);
    api.fetch.mockResolvedValueOnce(result("PENDING")); pending.resolve(); await flush();
    expect(retryButtons()).toHaveLength(0); expect(host.textContent).toContain("事件 1 已重新入队");
  });

  it("keeps the enqueue acknowledgement separate from refresh failure and requires a fresh query", async () => {
    api.fetch.mockRejectedValueOnce(new Error("follow-up unavailable")); retryButtons()[0]!.click(); await flush();
    expect(host.textContent).toContain("已重新入队"); expect(host.textContent).toContain("follow-up unavailable");
    expect(host.textContent).toContain("上次查询结果"); expect(host.textContent).not.toContain("反馈重试失败");
    expect(retryButtons().every(node => node.disabled)).toBe(true);
    refreshButton().click(); await flush(); expect(retryButtons().every(node => !node.disabled)).toBe(true);
    expect(api.retry).toHaveBeenCalledTimes(1);
  });

  it("labels the retained query during refresh and aborts the query when leaving the panel", async () => {
    const pending = deferred<GithubFeedbackDiagnostics>(); api.fetch.mockReturnValueOnce(pending.promise);
    refreshButton().click(); await flush(); expect(host.textContent).toContain("上次查询结果");
    expect(retryButtons().every(node => node.disabled)).toBe(true);
    const signal = api.fetch.mock.calls[1]![1].signal as AbortSignal; app!.unmount(); app = undefined;
    expect(signal.aborted).toBe(true); pending.resolve(result()); await flush(); expect(host.textContent).toBe("");
  });
});
