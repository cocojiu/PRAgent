import { createApp, h, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { disconnect, dispose, init, observe, resize, setOption, use } = vi.hoisted(() => ({
  disconnect: vi.fn(),
  dispose: vi.fn(),
  init: vi.fn(),
  observe: vi.fn(),
  resize: vi.fn(),
  setOption: vi.fn(),
  use: vi.fn()
}));

vi.mock("echarts/core", () => ({ init, use }));
vi.mock("echarts/charts", () => ({ BarChart: {}, LineChart: {}, PieChart: {} }));
vi.mock("echarts/components", () => ({
  AriaComponent: {},
  GraphicComponent: {},
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {}
}));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

import EChartPanel from "./EChartPanel.vue";

describe("EChartPanel", () => {
  let resizeCallback: ResizeObserverCallback;

  beforeEach(() => {
    vi.clearAllMocks();
    init.mockReturnValue({ dispose, resize, setOption });
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
    const app = createApp({
      render: () => h(EChartPanel, {
        accessibleLabel: "审查趋势图",
        option: { series: [] },
        summary: "过去两天共完成 12 次审查"
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
    expect(setOption).toHaveBeenCalledWith(expect.objectContaining({
      aria: {
        enabled: true,
        description: "审查趋势图。过去两天共完成 12 次审查",
        decal: { show: true }
      }
    }));

    resizeCallback([], {} as ResizeObserver);
    expect(resize).toHaveBeenCalledTimes(1);

    app.unmount();
    expect(disconnect).toHaveBeenCalledTimes(1);
    expect(dispose).toHaveBeenCalledTimes(1);
  });
});
