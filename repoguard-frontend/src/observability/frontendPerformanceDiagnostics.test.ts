import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  activateChartPerformanceTiming,
  beginChartPerformanceTiming,
  beginRoutePerformanceTiming,
  completeChartPerformanceTiming,
  completeRoutePerformanceTiming,
  enableFrontendPerformanceDiagnostics,
  recordRoutePerformanceMilestone
} from "./frontendPerformanceDiagnosticsBridge";
import {
  startFrontendPerformanceDiagnostics,
  stopFrontendPerformanceDiagnostics
} from "./frontendPerformanceDiagnostics";

describe("controlled frontend performance diagnostics", () => {
  const observerCallbacks = new Map<string, PerformanceObserverCallback>();
  let nowMs = 0;

  beforeEach(() => {
    observerCallbacks.clear();
    nowMs = 0;
    window.history.replaceState({}, "", "/repoguard/overview?performanceProfile=desktop");
    vi.spyOn(performance, "now").mockImplementation(() => nowMs);
    vi.spyOn(performance, "getEntriesByType").mockImplementation((type) => {
      if (type === "navigation") {
        return [{ entryType: "navigation", type: "reload" } as PerformanceNavigationTiming];
      }
      if (type === "resource") {
        return [
          resource("/assets/echarts-core.js", 1200, 1000, 4000),
          resource("/assets/zrender-canvas.js", 0, 800, 3200),
          resource("/assets/vendor.js", 900, 700, 1800)
        ];
      }
      return [];
    });
    vi.stubGlobal("PerformanceObserver", class {
      static supportedEntryTypes = ["largest-contentful-paint", "layout-shift", "event"];

      constructor(callback: PerformanceObserverCallback) {
        this.callback = callback;
      }

      private readonly callback: PerformanceObserverCallback;

      observe(options: PerformanceObserverInit) {
        observerCallbacks.set(String(options.type), this.callback);
      }

      disconnect = vi.fn();
      takeRecords = vi.fn(() => []);
    });
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
      callback(nowMs);
      return 1;
    });
  });

  afterEach(() => {
    stopFrontendPerformanceDiagnostics();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    window.history.replaceState({}, "", "/");
  });

  it("exposes a repeatable snapshot with vitals, route, chart and cache measurements", () => {
    enableFrontendPerformanceDiagnostics();
    nowMs = 100;
    beginRoutePerformanceTiming("overview");

    expect(startFrontendPerformanceDiagnostics(() => "overview")).toBe(true);
    emit("largest-contentful-paint", [
      { entryType: "largest-contentful-paint", startTime: 1800 } as PerformanceEntry
    ]);
    emit("layout-shift", [
      layoutShift(0, 0.06),
      layoutShift(600, 0.05),
      layoutShift(1800, 0.04)
    ]);
    emit("event", [
      interaction(1, 80),
      interaction(1, 120),
      interaction(2, 240)
    ]);

    nowMs = 220;
    completeRoutePerformanceTiming("overview");
    nowMs = 480;
    recordRoutePerformanceMilestone("overview", "data-ready");
    nowMs = 230;
    beginChartPerformanceTiming("审查趋势图");
    nowMs = 260;
    activateChartPerformanceTiming("审查趋势图");
    nowMs = 620;
    completeChartPerformanceTiming("审查趋势图");

    const snapshot = window.__REPOGUARD_PERFORMANCE__?.snapshot();
    const publishedSnapshot = JSON.parse(
      document.querySelector("#repoguard-performance-diagnostics")?.textContent ?? "null"
    );

    expect(snapshot).toMatchObject({
      schemaVersion: 1,
      profile: "desktop",
      ready: true,
      context: {
        route: "overview",
        navigationType: "reload"
      },
      webVitals: {
        lcp: { value: 1800, rating: "good" },
        inp: { value: 240, rating: "needs-improvement" },
        cls: { value: 0.11, rating: "needs-improvement" }
      },
      routes: [
        {
          route: "overview",
          startedAtMs: 100,
          paintedAtMs: 220,
          durationMs: 120
        }
      ],
      milestones: [
        {
          route: "overview",
          name: "data-ready",
          durationMs: 380
        }
      ],
      charts: [
        {
          label: "审查趋势图",
          route: "overview",
          routeToReadyMs: 520,
          mountedToReadyMs: 390,
          idleWaitMs: 30,
          renderMs: 360
        }
      ],
      pendingCharts: [],
      chartResources: {
        cacheState: "mixed",
        requestCount: 2,
        networkRequestCount: 1,
        cachedRequestCount: 1,
        transferBytes: 1200,
        encodedBodyBytes: 1800,
        decodedBodyBytes: 7200
      }
    });
    expect(publishedSnapshot).toMatchObject({
      profile: "desktop",
      ready: true,
      chartResources: { cacheState: "mixed" }
    });
  });

  it("stays disabled when the requested profile is not supported", () => {
    window.history.replaceState({}, "", "/repoguard/overview?performanceProfile=random");
    enableFrontendPerformanceDiagnostics();

    expect(startFrontendPerformanceDiagnostics(() => "overview")).toBe(false);
    expect(window.__REPOGUARD_PERFORMANCE__).toBeUndefined();
  });

  const emit = (type: string, entries: PerformanceEntry[]) => {
    observerCallbacks.get(type)?.(
      { getEntries: () => entries } as PerformanceObserverEntryList,
      {} as PerformanceObserver
    );
  };
});

const resource = (
  name: string,
  transferSize: number,
  encodedBodySize: number,
  decodedBodySize: number
) => ({
  entryType: "resource",
  name: `https://repoguard.example.com${name}`,
  transferSize,
  encodedBodySize,
  decodedBodySize
} as PerformanceResourceTiming);

const layoutShift = (startTime: number, value: number) => ({
  entryType: "layout-shift",
  startTime,
  value,
  hadRecentInput: false
} as unknown as PerformanceEntry);

const interaction = (interactionId: number, duration: number) => ({
  entryType: "event",
  startTime: 0,
  duration,
  interactionId
} as unknown as PerformanceEntry);
