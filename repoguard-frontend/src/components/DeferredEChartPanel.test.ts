import { createApp, defineComponent, h, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { dispose, init, resize, setOption, use } = vi.hoisted(() => ({
  dispose: vi.fn(),
  init: vi.fn(),
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

import DeferredEChartPanel from "./DeferredEChartPanel.vue";

describe("DeferredEChartPanel", () => {
  const disconnect = vi.fn();
  const observe = vi.fn();
  const requestIdleCallback = vi.fn();
  const cancelIdleCallback = vi.fn();
  let intersectionCallback: IntersectionObserverCallback;
  let idleCallback: IdleRequestCallback;

  beforeEach(() => {
    vi.clearAllMocks();
    init.mockReturnValue({ dispose, resize, setOption });
    vi.stubGlobal("IntersectionObserver", class {
      constructor(callback: IntersectionObserverCallback) {
        intersectionCallback = callback;
      }

      disconnect = disconnect;
      observe = observe;
      unobserve = vi.fn();
    });
    vi.stubGlobal("ResizeObserver", class {
      disconnect = vi.fn();
      observe = vi.fn();
      unobserve = vi.fn();
    });
    requestIdleCallback.mockImplementation((callback: IdleRequestCallback) => {
      idleCallback = callback;
      return 41;
    });
    vi.stubGlobal("requestIdleCallback", requestIdleCallback);
    vi.stubGlobal("cancelIdleCallback", cancelIdleCallback);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = "";
  });

  const mountDeferredChart = () => {
    const host = document.createElement("div");
    document.body.append(host);
    const app = createApp(defineComponent({
      setup() {
        return () => h(DeferredEChartPanel, {
          accessibleLabel: "审查趋势图",
          option: { series: [] },
          summary: "最近七天审查趋势"
        });
      }
    }));
    app.mount(host);
    return { app, host };
  };

  it("cancels a pending idle render when the chart leaves the page", async () => {
    const { app, host } = mountDeferredChart();
    await nextTick();

    expect(host.querySelector(".deferred-chart-placeholder")).not.toBeNull();
    expect(init).not.toHaveBeenCalled();
    intersectionCallback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver
    );
    expect(requestIdleCallback).toHaveBeenCalledTimes(1);

    app.unmount();

    expect(cancelIdleCallback).toHaveBeenCalledWith(41);
    expect(init).not.toHaveBeenCalled();
  });

  it("loads the chart runtime only after visibility and an idle opportunity", async () => {
    const { app, host } = mountDeferredChart();
    await nextTick();

    expect(init).not.toHaveBeenCalled();
    intersectionCallback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver
    );
    expect(init).not.toHaveBeenCalled();

    idleCallback({ didTimeout: false, timeRemaining: () => 50 });
    await vi.dynamicImportSettled();
    await nextTick();

    expect(init).toHaveBeenCalledTimes(1);
    expect(host.querySelector(".chart-panel")?.getAttribute("aria-label")).toBe("审查趋势图");
    expect(host.querySelector(".deferred-chart-placeholder")).toBeNull();

    app.unmount();
    expect(disconnect).toHaveBeenCalled();
  });
});
