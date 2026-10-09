import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BackupStatus } from "@/types";
import Panel from "./BackupStatusPanel.vue";

/* eslint-disable vue/one-component-per-file -- Small UI stubs exercise the real panel and its async refresh lifecycle. */
const api = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock("@/api/config", () => ({ fetchBackupStatus: api.fetch }));
let app: App | undefined;
let host: HTMLDivElement;
const flush = async () => { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); } };
const result = (status = "SUCCESS_RECORDED", checkedAt = "2026-10-09T08:00:00Z") => ({
  status, checkedAt, maxAgeHours: 48, retained: 3, archiveBytes: 1048576
}) as BackupStatus;
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const mount = async () => {
  app = createApp(Panel);
  app.component("ElButton", defineComponent({ props: { loading: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" }, type: { type: String, default: "" } },
    setup: (props) => () => h("div", { role: "alert", "data-type": props.type }, props.title) }));
  app.component("ElDescriptions", defineComponent({ setup: (_, { slots }) => () => h("dl", slots.default?.()) }));
  app.component("ElDescriptionsItem", defineComponent({ props: { label: { type: String, default: "" } },
    setup: (props, { slots }) => () => h("div", [props.label, slots.default?.()]) }));
  app.mount(host); await flush();
};
beforeEach(() => { vi.resetAllMocks(); host = document.createElement("div"); document.body.append(host); api.fetch.mockResolvedValue(result()); });
afterEach(() => { app?.unmount(); app = undefined; host.remove(); });

describe("backup record refresh", () => {
  it("retains the last query while refreshing and after failure with an explicit stale label", async () => {
    await mount(); const previousText = host.querySelector("dl")!.textContent;
    const pending = deferred<BackupStatus>(); api.fetch.mockReturnValueOnce(pending.promise);
    host.querySelector("button")!.click(); await flush();
    expect(host.querySelector("dl")!.textContent).toBe(previousText);
    expect(host.textContent).toContain("当前显示上次查询结果"); expect(host.textContent).toContain("尚未确认最新状态");
    expect(host.querySelector("button")!.disabled).toBe(true);
    pending.reject(new Error("read offline")); await flush();
    expect(host.textContent).toContain("read offline"); expect(host.querySelector("dl")!.textContent).toBe(previousText);
    expect(host.textContent).toContain("上次查询结果：最近执行记录成功");
    expect(host.querySelector("button")!.disabled).toBe(false);
    expect(host.textContent).toContain("执行记录成功不等于备份可恢复");
  });

  it("replaces the retained record and clears its warning after a successful query", async () => {
    await mount(); api.fetch.mockRejectedValueOnce(new Error("offline"));
    host.querySelector("button")!.click(); await flush();
    api.fetch.mockResolvedValueOnce(result("FAILED", "2026-10-09T09:00:00Z"));
    host.querySelector("button")!.click(); await flush();
    expect(host.textContent).toContain("最近执行失败"); expect(host.textContent).not.toContain("当前显示上次查询结果");
    expect(host.textContent).not.toContain("offline"); expect(host.textContent).not.toContain("最近执行记录成功");
    expect(host.querySelector("dl")!.textContent).toContain("17:00:00");
  });

  it("does not invent a last successful query when the initial read fails", async () => {
    api.fetch.mockRejectedValueOnce(new Error("first read offline")); await mount();
    expect(host.querySelector("dl")).toBeNull(); expect(host.textContent).toContain("first read offline");
    expect(host.textContent).not.toContain("当前显示上次查询结果");
  });

  it("aborts the read on unmount and ignores its late result", async () => {
    const pending = deferred<BackupStatus>(); api.fetch.mockReturnValueOnce(pending.promise); await mount();
    const signal = api.fetch.mock.calls[0]![0].signal as AbortSignal;
    app!.unmount(); app = undefined; expect(signal.aborted).toBe(true);
    pending.resolve(result()); await flush(); expect(host.textContent).toBe("");
  });
});
