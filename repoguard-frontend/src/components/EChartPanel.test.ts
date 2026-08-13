import { createApp, h, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { disconnect, dispose, init, observe, off, on, resize, setOption, use } = vi.hoisted(() => ({
  disconnect: vi.fn(),
  dispose: vi.fn(),
  init: vi.fn(),
  observe: vi.fn(),
  off: vi.fn(),
  on: vi.fn(),
  resize: vi.fn(),
  setOption: vi.fn(),
  use: vi.fn()
}));

vi.mock("echarts/core", () => ({ init, use }));
vi.mock("echarts/charts", () => ({ BarChart: {}, LineChart: {} }));
vi.mock("echarts/components", () => ({
  GridComponent: {},
  TooltipComponent: {}
}));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

import EChartPanel from "./EChartPanel.vue";

describe("EChartPanel", () => {
  let resizeCallback: ResizeObserverCallback;
  let finishedCallback: () => void;

  beforeEach(() => {
    vi.clearAllMocks();
    on.mockImplementation((event: string, callback: () => void) => {
      if (event === "finished") {
        finishedCallback = callback;
      }
    });
    init.mockReturnValue({ dispose, off, on, resize, setOption });
    vi.stubGlobal("ResizeObserver", class {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback;
      }

      disconnect = disconnect;
      observe = observe;
      unobserve = vi.fn();
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = "";
  });

  it("observes its container and exposes an accessible chart summary", async () => {
    const host = document.createElement("div");
    document.body.append(host);
    const rendered = vi.fn();
    const app = createApp({
      render: () => h(EChartPanel, {
        accessibleLabel: "审查趋势图",
        option: { series: [] },
        summary: "过去两天共完成 12 次审查",
        onRendered: rendered
      })
    });

    app.mount(host);
    await nextTick();

    const chart = host.querySelector<HTMLElement>(".chart-panel");
    const summary = host.querySelector<HTMLElement>(".chart-panel-summary");
    expect(init).toHaveBeenCalledWith(chart);
    expect(observe).toHaveBeenCalledWith(chart);
    expect(chart?.getAttribute("role")).toBe("img");
    expect(chart?.getAttribute("aria-label")).toBe("审查趋势图");
    expect(chart?.getAttribute("aria-describedby")).toBe(summary?.id);
    expect(summary?.textContent).toContain("12 次审查");
    expect(setOption).toHaveBeenCalledWith({ series: [] });
    expect(on).toHaveBeenCalledWith("finished", expect.any(Function));
    finishedCallback();
    finishedCallback();
    expect(rendered).toHaveBeenCalledTimes(1);

    resizeCallback([], {} as ResizeObserver);
    expect(resize).toHaveBeenCalledTimes(1);

    app.unmount();
    expect(off).toHaveBeenCalledWith("finished", expect.any(Function));
    expect(disconnect).toHaveBeenCalledTimes(1);
    expect(dispose).toHaveBeenCalledTimes(1);
  });
});
