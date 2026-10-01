import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type * as ConfigApi from "@/api/config";

/* eslint-disable vue/one-component-per-file -- Lightweight component stubs for this workbench test. */

const api = vi.hoisted(() => ({ load: vi.fn(), start: vi.fn(), cancel: vi.fn(), reports: vi.fn() }));
vi.mock("@/api/config", async (original) => ({
  ...await original<typeof ConfigApi>(),
  fetchLlmEvaluationRun: api.load,
  startLlmEvaluationRun: api.start,
  cancelLlmEvaluationRun: api.cancel,
  fetchLlmEvaluationReports: api.reports
}));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: { success: vi.fn(), error: vi.fn() } }));
import Workbench from "./LlmEvaluationWorkbench.vue";

let app: App | null;
let host: HTMLDivElement;
const run = { runId: "saved-run", runKey: "saved-diagnostic", status: "COMPLETE", totalSamples: 2,
  completedSamples: 2, totalTokens: 19058, totalCost: 0.060352,
  diagnostics: { sampleIds: ["pr-89", "pr-97"], samples: [] } };
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); };
const load = async (id = " saved-run ") => {
  const input = host.querySelector<HTMLInputElement>('input[aria-label="已有运行 ID"]')!;
  input.value = id;
  input.dispatchEvent(new Event("input"));
  await nextTick();
  [...host.querySelectorAll("button")].find(button => button.textContent === "查看已有运行")!.click();
  await flush();
};

beforeEach(async () => {
  vi.useFakeTimers();
  vi.resetAllMocks();
  api.reports.mockResolvedValue([]);
  api.load.mockResolvedValue(run);
  host = document.createElement("div");
  document.body.append(host);
  app = createApp(Workbench);
  app.directive("loading", () => {});
  app.component("ElInput", defineComponent({ props: { modelValue: { type: String, default: "" } }, emits: ["update:modelValue"],
    setup: (props, { emit, attrs }) => () => h("input", { ...attrs, value: props.modelValue,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }) }));
  app.component("ElButton", defineComponent({ props: { loading: Boolean, disabled: Boolean },
    setup: (props, { slots }) => () => h("button", { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component("ElAlert", defineComponent({ props: { title: { type: String, default: "" } },
    setup: (props, { slots }) => () => h("div", [props.title, slots.default?.()]) }));
  for (const name of ["ElTable", "ElTag"]) app.component(name, defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) }));
  for (const name of ["ElTableColumn", "ElInputNumber", "ElSwitch"]) app.component(name, defineComponent({ setup: () => () => null }));
  app.mount(host);
  await flush();
});
afterEach(() => { app?.unmount(); app = null; host.remove(); vi.useRealTimers(); });

it("reads saved diagnostics without starting a run or polling terminal results", async () => {
  await load();
  expect(api.load).toHaveBeenCalledWith("saved-run", { signal: expect.any(AbortSignal) });
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
  expect(host.textContent).toContain("19058 tokens");
  expect(host.textContent).toContain("运行 ID：saved-run");
  await vi.advanceTimersByTimeAsync(6000);
  expect(api.load).toHaveBeenCalledTimes(1);
  expect(api.start).not.toHaveBeenCalled();
});

it("rejects blank IDs and clears stale results when loading fails", async () => {
  await load(" ");
  expect(api.load).not.toHaveBeenCalled();
  expect(host.textContent).toContain("请输入有效的已有运行 ID");
  await load();
  api.load.mockRejectedValueOnce(new Error("运行不存在或无访问权限"));
  await load("missing-run");
  expect(host.textContent).toContain("运行不存在或无访问权限");
  expect(host.textContent).not.toContain("saved-diagnostic：COMPLETE");
  expect(api.start).not.toHaveBeenCalled();
});

it("resumes status polling for an active run and stops at completion", async () => {
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  await load();
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(api.load).toHaveBeenCalledTimes(2);
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
  expect(api.start).not.toHaveBeenCalled();
});

it("does not resume polling after an in-flight read completes on an unmounted page", async () => {
  let finish!: (value: typeof run) => void;
  api.load.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await load();
  app!.unmount();
  app = null;
  finish({ ...run, status: "RUNNING" });
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(api.load).toHaveBeenCalledTimes(1);
  expect(api.start).not.toHaveBeenCalled();
});

it("keeps only one poll in flight when a status request is slow", async () => {
  let finish!: (value: typeof run) => void;
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  api.load.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  await load();
  await vi.advanceTimersByTimeAsync(10000);
  expect(api.load).toHaveBeenCalledTimes(2);
  finish(run);
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
  expect(api.load).toHaveBeenCalledTimes(2);
});

it("ignores a start response after the page is unmounted", async () => {
  let finish!: (value: typeof run) => void;
  api.start.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  [...host.querySelectorAll("button")].find(button => button.textContent === "启动评估")!.click();
  await flush();
  app!.unmount();
  app = null;
  finish({ ...run, status: "RUNNING" });
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(api.load).not.toHaveBeenCalled();
});

it("resumes polling when cancelling a running evaluation fails", async () => {
  api.load.mockResolvedValue({ ...run, status: "RUNNING" });
  api.cancel.mockRejectedValueOnce(new Error("temporary failure"));
  await load();
  [...host.querySelectorAll("button")].find(button => button.textContent === "取消运行")!.click();
  await flush();
  const previousCalls = api.load.mock.calls.length;
  api.load.mockResolvedValueOnce(run);
  await vi.advanceTimersByTimeAsync(4000);
  expect(api.load.mock.calls.length).toBeGreaterThan(previousCalls);
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
});

it("does not allow an old running response to overwrite cancellation", async () => {
  let finish!: (value: typeof run) => void;
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  api.load.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  api.cancel.mockResolvedValueOnce({ ...run, status: "CANCELLED" });
  await load();
  const signal = api.load.mock.calls[1]![1].signal as AbortSignal;
  [...host.querySelectorAll("button")].find(button => button.textContent === "取消运行")!.click();
  await flush();
  expect(signal.aborted).toBe(true);
  finish({ ...run, status: "RUNNING" });
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(host.textContent).toContain("saved-diagnostic：CANCELLED");
  expect(api.load).toHaveBeenCalledTimes(2);
});

it("ignores an old poll when switching to another saved run", async () => {
  let finish!: (value: typeof run) => void;
  const other = { ...run, runId: "other-run", runKey: "other-diagnostic" };
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  api.load.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  api.load.mockResolvedValueOnce(other);
  await load();
  await load("other-run");
  finish({ ...run, status: "RUNNING" });
  await flush();
  expect(host.textContent).toContain("other-diagnostic：COMPLETE");
  expect(host.textContent).not.toContain("saved-diagnostic：RUNNING");
});

it("bounds transient error retries and retains the last successful state", async () => {
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  api.load.mockRejectedValue(new Error("temporarily unavailable"));
  await load();
  await vi.advanceTimersByTimeAsync(120000);
  expect(api.load).toHaveBeenCalledTimes(5);
  expect(host.textContent).toContain("saved-diagnostic：RUNNING");
  expect(host.textContent).toContain("状态更新");
  expect(host.textContent).toContain("temporarily unavailable");
  api.load.mockResolvedValue(run);
  [...host.querySelectorAll("button")].find(button => button.textContent === "刷新运行状态")!.click();
  await flush();
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
});

it("pauses in a hidden page and reads once when visible again", async () => {
  api.load.mockResolvedValue({ ...run, status: "RUNNING" });
  await load();
  const descriptor = Object.getOwnPropertyDescriptor(document, "hidden");
  try {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    document.dispatchEvent(new Event("visibilitychange"));
    const before = api.load.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10000);
    expect(api.load).toHaveBeenCalledTimes(before);
    api.load.mockResolvedValueOnce(run);
    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();
    expect(api.load).toHaveBeenCalledTimes(before + 1);
    expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
  } finally {
    if (descriptor) Object.defineProperty(document, "hidden", descriptor);
    else Reflect.deleteProperty(document, "hidden");
  }
});

it("does not regress a terminal run when manually refreshing its state", async () => {
  await load();
  api.load.mockResolvedValueOnce({ ...run, status: "RUNNING" });
  [...host.querySelectorAll("button")].find(button => button.textContent === "刷新运行状态")!.click();
  await flush();
  await vi.advanceTimersByTimeAsync(6000);
  expect(host.textContent).toContain("saved-diagnostic：COMPLETE");
  expect(api.load).toHaveBeenCalledTimes(2);
});
