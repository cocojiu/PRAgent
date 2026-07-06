import { apiRequest } from "@/api/contracts";
import { hasAuthToken } from "@/api/client";
import {
  configureFrontendPerformanceBuffer,
  drainFrontendPerformanceReport,
  hasFrontendPerformanceObservations,
  observeFrontendApiRequest,
  observeFrontendLongTask
} from "@/observability/frontendPerformanceBuffer";
import type { FrontendApiRequestObservation } from "@/observability/frontendPerformanceBuffer";

export { observeFrontendApiRequest };
export type { FrontendApiRequestObservation };

type RouteResolver = () => string | undefined;

const INITIAL_OBSERVATION_WINDOW_MS = 6000;
const FLUSH_DELAY_MS = 1200;

let routeResolver: RouteResolver = () => undefined;
let observationStarted = false;
let initialStartedAtMs = 0;
let flushTimer: number | undefined;
let longTaskObserver: PerformanceObserver | undefined;

export const startFrontendPerformanceObservation = (resolveRoute: RouteResolver) => {
  if (observationStarted || typeof window === "undefined") {
    return;
  }
  observationStarted = true;
  routeResolver = resolveRoute;
  initialStartedAtMs = now();
  configureFrontendPerformanceBuffer({
    shouldRecord: shouldRecordInitialObservation,
    scheduleFlush
  });
  observeLongTasks();
  window.addEventListener("visibilitychange", flushWhenHidden);
};

const observeLongTasks = () => {
  if (longTaskObserver !== undefined) {
    return;
  }
  if (!("PerformanceObserver" in window)) {
    return;
  }
  const supportedEntryTypes = PerformanceObserver.supportedEntryTypes ?? [];
  if (!supportedEntryTypes.includes("longtask")) {
    return;
  }
  longTaskObserver = new PerformanceObserver((list) => {
    list.getEntries().forEach((entry) => {
      observeFrontendLongTask({
        startedAtMs: entry.startTime,
        durationMs: entry.duration
      });
    });
  });
  longTaskObserver.observe({ entryTypes: ["longtask"] });
};

const scheduleFlush = () => {
  if (flushTimer !== undefined) {
    return;
  }
  flushTimer = window.setTimeout(() => {
    flushTimer = undefined;
    void flushFrontendPerformance();
  }, FLUSH_DELAY_MS);
};

const flushWhenHidden = () => {
  if (document.visibilityState === "hidden") {
    void flushFrontendPerformance();
  }
};

const flushFrontendPerformance = async () => {
  if (!hasAuthToken() || !hasFrontendPerformanceObservations()) {
    return;
  }
  const report = drainFrontendPerformanceReport(routeResolver());
  try {
    await apiRequest("reportFrontendPerformance", report);
  } catch {
    // Observability must not affect user workflows.
  }
};

const shouldRecordInitialObservation = () =>
  observationStarted && now() - initialStartedAtMs <= INITIAL_OBSERVATION_WINDOW_MS;

const now = () => (typeof performance === "undefined" ? Date.now() : performance.now());
