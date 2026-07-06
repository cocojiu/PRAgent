import { hasAuthToken, request } from "@/api/client";

type ApiRequestResult = "success" | "failed";

export type FrontendApiRequestObservation = {
  operation: string;
  path?: string;
  method: string;
  status?: number;
  result: ApiRequestResult;
  startedAtMs: number;
  durationMs: number;
};

type FrontendLongTaskObservation = {
  startedAtMs: number;
  durationMs: number;
};

type FrontendPerformanceReport = {
  route: string;
  apiRequests: FrontendApiRequestObservation[];
  longTasks: FrontendLongTaskObservation[];
};

type RouteResolver = () => string | undefined;

const INITIAL_OBSERVATION_WINDOW_MS = 6000;
const FLUSH_DELAY_MS = 1200;
const MAX_API_REQUESTS = 50;
const MAX_LONG_TASKS = 50;
const OBSERVABILITY_ENDPOINT = "/api/v1/observability/frontend/performance";

let routeResolver: RouteResolver = () => undefined;
let observationStarted = false;
let initialStartedAtMs = 0;
let flushTimer: number | undefined;
let longTaskObserver: PerformanceObserver | undefined;
const apiRequests: FrontendApiRequestObservation[] = [];
const longTasks: FrontendLongTaskObservation[] = [];

export const startFrontendPerformanceObservation = (resolveRoute: RouteResolver) => {
  if (observationStarted || typeof window === "undefined") {
    return;
  }
  observationStarted = true;
  routeResolver = resolveRoute;
  initialStartedAtMs = now();
  observeLongTasks();
  window.addEventListener("visibilitychange", flushWhenHidden);
};

export const observeFrontendApiRequest = (observation: FrontendApiRequestObservation) => {
  if (!shouldRecordInitialObservation()) {
    return;
  }
  if (apiRequests.length >= MAX_API_REQUESTS) {
    return;
  }
  apiRequests.push({
    ...observation,
    operation: stableText(observation.operation),
    path: stableText(observation.path),
    method: stableText(observation.method),
    status: observation.status && observation.status > 0 ? observation.status : undefined,
    startedAtMs: nonNegative(observation.startedAtMs),
    durationMs: nonNegative(observation.durationMs)
  });
  scheduleFlush();
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
      if (!shouldRecordInitialObservation() || longTasks.length >= MAX_LONG_TASKS) {
        return;
      }
      longTasks.push({
        startedAtMs: nonNegative(entry.startTime),
        durationMs: nonNegative(entry.duration)
      });
      scheduleFlush();
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
  if (!hasAuthToken() || (apiRequests.length === 0 && longTasks.length === 0)) {
    return;
  }
  const report: FrontendPerformanceReport = {
    route: stableText(routeResolver()),
    apiRequests: apiRequests.splice(0, apiRequests.length),
    longTasks: longTasks.splice(0, longTasks.length)
  };
  try {
    await request<void>(OBSERVABILITY_ENDPOINT, undefined, {
      method: "POST",
      body: JSON.stringify(report),
      keepalive: true
    });
  } catch {
    // Observability must not affect user workflows.
  }
};

const shouldRecordInitialObservation = () =>
  observationStarted && now() - initialStartedAtMs <= INITIAL_OBSERVATION_WINDOW_MS;

const now = () => (typeof performance === "undefined" ? Date.now() : performance.now());

const nonNegative = (value: number | undefined) => {
  if (value === undefined || !Number.isFinite(value) || value < 0) {
    return 0;
  }
  return Math.round(value);
};

const stableText = (value: string | undefined) => {
  const normalized = value?.trim();
  return normalized || "unknown";
};
