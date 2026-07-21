import {
  CONTROLLED_PERFORMANCE_PROFILES,
  disableFrontendPerformanceDiagnostics,
  installFrontendPerformanceDiagnosticSink
} from "@/observability/frontendPerformanceDiagnosticsBridge";
import type {
  ControlledPerformanceProfile,
  FrontendPerformanceDiagnosticEvent
} from "@/observability/frontendPerformanceDiagnosticsBridge";

type MetricRating = "good" | "needs-improvement" | "poor" | "unavailable";
type CacheState = "network" | "cache" | "mixed" | "none";
type RouteResolver = () => string | undefined;

export type FrontendPerformanceDiagnosticSnapshot = {
  schemaVersion: 1;
  profile: ControlledPerformanceProfile;
  capturedAt: string;
  ready: boolean;
  context: {
    route: string;
    navigationType: string;
    effectiveNetworkType: string;
    saveData: boolean;
    viewportWidth: number;
    viewportHeight: number;
    devicePixelRatio: number;
    hardwareConcurrency: number;
    deviceMemoryGiB?: number;
  };
  webVitals: {
    lcp: RatedLcpMetric;
    inp: RatedMetric;
    cls: RatedMetric;
  };
  navigation: NavigationTimingSummary | null;
  startupResources: StartupResourceSummary;
  routes: RouteTiming[];
  milestones: RouteMilestone[];
  charts: ChartTiming[];
  pendingCharts: string[];
  chartResources: ChartResourceSummary;
};

type RatedMetric = {
  value: number | null;
  rating: MetricRating;
};

type RatedLcpMetric = RatedMetric & {
  attribution: LargestContentfulPaintAttribution | null;
};

type LargestContentfulPaintAttribution = {
  tagName: string;
  id: string | null;
  classNames: string[];
  resourcePath: string | null;
  size: number;
  loadTimeMs: number;
  renderTimeMs: number;
};

type NavigationTimingSummary = {
  protocol: string;
  redirectMs: number;
  dnsMs: number;
  connectMs: number;
  tlsMs: number;
  requestToFirstByteMs: number;
  responseDownloadMs: number;
  responseEndAtMs: number;
  domInteractiveAtMs: number;
  domContentLoadedAtMs: number;
  loadEventAtMs: number;
  appRouteStartAtMs: number | null;
  responseEndToRouteStartMs: number | null;
  transferBytes: number;
  encodedBodyBytes: number;
  decodedBodyBytes: number;
};

type StartupResourceTiming = {
  path: string;
  initiatorType: string;
  protocol: string;
  cacheState: Exclude<CacheState, "mixed" | "none">;
  startAtMs: number;
  responseEndAtMs: number;
  durationMs: number;
  transferBytes: number;
  encodedBodyBytes: number;
  decodedBodyBytes: number;
};

type StartupResourceSummary = {
  cacheState: CacheState;
  requestCount: number;
  networkRequestCount: number;
  cachedRequestCount: number;
  transferBytes: number;
  loadEndAtMs: number;
  resources: StartupResourceTiming[];
};

type RouteTiming = {
  route: string;
  startedAtMs: number;
  paintedAtMs: number;
  durationMs: number;
};

type RouteMilestone = {
  route: string;
  name: string;
  reachedAtMs: number;
  durationMs: number;
};

type PendingChartTiming = {
  label: string;
  route: string;
  routeStartedAtMs?: number;
  mountedAtMs: number;
  activatedAtMs?: number;
};

type ChartTiming = {
  label: string;
  route: string;
  mountedAtMs: number;
  activatedAtMs: number | null;
  renderedAtMs: number;
  routeToReadyMs: number | null;
  mountedToReadyMs: number;
  idleWaitMs: number | null;
  renderMs: number | null;
};

type ChartResourceSummary = {
  cacheState: CacheState;
  requestCount: number;
  networkRequestCount: number;
  cachedRequestCount: number;
  transferBytes: number;
  encodedBodyBytes: number;
  decodedBodyBytes: number;
};

type LayoutShiftEntry = PerformanceEntry & {
  value: number;
  hadRecentInput: boolean;
};

type EventTimingEntry = PerformanceEntry & {
  duration: number;
  interactionId: number;
};

type LargestContentfulPaintEntry = PerformanceEntry & {
  element?: Element | null;
  id?: string;
  url?: string;
  size?: number;
  loadTime?: number;
  renderTime?: number;
};

type NavigatorWithDiagnostics = Navigator & {
  connection?: {
    effectiveType?: string;
    saveData?: boolean;
  };
  deviceMemory?: number;
};

type DiagnosticWindowApi = {
  readonly schemaVersion: 1;
  readonly profile: ControlledPerformanceProfile;
  snapshot: () => FrontendPerformanceDiagnosticSnapshot;
  reset: () => void;
};

declare global {
  interface Window {
    __REPOGUARD_PERFORMANCE__?: DiagnosticWindowApi;
  }
}

const MAX_RECORDED_ITEMS = 30;
const routeStarts = new Map<string, number>();
const routeTimings: RouteTiming[] = [];
const routeMilestones: RouteMilestone[] = [];
const pendingCharts = new Map<string, PendingChartTiming>();
const chartTimings: ChartTiming[] = [];
const interactionDurations = new Map<number, number>();
const observers: PerformanceObserver[] = [];

let activeProfile: ControlledPerformanceProfile | undefined;
let routeResolver: RouteResolver = () => undefined;
let currentRoute = "unknown";
let largestContentfulPaintMs: number | null = null;
let largestContentfulPaintAttribution: LargestContentfulPaintAttribution | null = null;
let cumulativeLayoutShift = 0;
let layoutShiftSessionValue = 0;
let layoutShiftSessionStartedAtMs: number | null = null;
let lastLayoutShiftAtMs = 0;
let diagnosticOutput: HTMLOutputElement | null = null;

export const startFrontendPerformanceDiagnostics = (resolveRoute: RouteResolver) => {
  if (activeProfile || typeof window === "undefined") {
    return false;
  }
  const profile = resolveProfile(window.location.search);
  if (!profile) {
    disableFrontendPerformanceDiagnostics();
    return false;
  }

  activeProfile = profile;
  routeResolver = resolveRoute;
  installFrontendPerformanceDiagnosticSink(handleDiagnosticEvent);
  observeWebVitals();
  window.__REPOGUARD_PERFORMANCE__ = {
    schemaVersion: 1,
    profile,
    snapshot: createSnapshot,
    reset: resetMeasurements
  };
  publishSnapshot();
  return true;
};

export const stopFrontendPerformanceDiagnostics = () => {
  observers.splice(0, observers.length).forEach((observer) => observer.disconnect());
  disableFrontendPerformanceDiagnostics();
  if (typeof window !== "undefined") {
    delete window.__REPOGUARD_PERFORMANCE__;
  }
  diagnosticOutput?.remove();
  diagnosticOutput = null;
  activeProfile = undefined;
  routeResolver = () => undefined;
  resetMeasurements();
};

const handleDiagnosticEvent = (event: FrontendPerformanceDiagnosticEvent) => {
  switch (event.type) {
    case "route-started":
      currentRoute = stableText(event.route);
      routeStarts.set(currentRoute, event.atMs);
      break;
    case "route-painted":
      recordRoutePaint(event);
      break;
    case "route-milestone":
      recordRouteMilestone(event);
      break;
    case "chart-mounted":
      recordChartMounted(event);
      break;
    case "chart-activated":
      updateChartActivation(event);
      break;
    case "chart-rendered":
      recordChartRendered(event);
      break;
    case "chart-cancelled":
      pendingCharts.delete(stableText(event.label));
      break;
  }
  publishSnapshot();
};

const recordRoutePaint = (event: Extract<FrontendPerformanceDiagnosticEvent, { type: "route-painted" }>) => {
  const route = stableText(event.route);
  const startedAtMs = routeStarts.get(route);
  if (startedAtMs === undefined) {
    return;
  }
  pushBounded(routeTimings, {
    route,
    startedAtMs: rounded(startedAtMs),
    paintedAtMs: rounded(event.atMs),
    durationMs: elapsed(startedAtMs, event.atMs)
  });
};

const recordRouteMilestone = (
  event: Extract<FrontendPerformanceDiagnosticEvent, { type: "route-milestone" }>
) => {
  const route = stableText(event.route);
  const startedAtMs = routeStarts.get(route);
  if (startedAtMs === undefined) {
    return;
  }
  pushBounded(routeMilestones, {
    route,
    name: stableText(event.name),
    reachedAtMs: rounded(event.atMs),
    durationMs: elapsed(startedAtMs, event.atMs)
  });
};

const recordChartMounted = (
  event: Extract<FrontendPerformanceDiagnosticEvent, { type: "chart-mounted" }>
) => {
  const label = stableText(event.label);
  pendingCharts.set(label, {
    label,
    route: currentRoute,
    routeStartedAtMs: routeStarts.get(currentRoute),
    mountedAtMs: event.atMs
  });
};

const updateChartActivation = (
  event: Extract<FrontendPerformanceDiagnosticEvent, { type: "chart-activated" }>
) => {
  const pending = pendingCharts.get(stableText(event.label));
  if (pending) {
    pending.activatedAtMs = event.atMs;
  }
};

const recordChartRendered = (
  event: Extract<FrontendPerformanceDiagnosticEvent, { type: "chart-rendered" }>
) => {
  const label = stableText(event.label);
  const pending = pendingCharts.get(label);
  if (!pending) {
    return;
  }
  pendingCharts.delete(label);
  pushBounded(chartTimings, {
    label,
    route: pending.route,
    mountedAtMs: rounded(pending.mountedAtMs),
    activatedAtMs: pending.activatedAtMs === undefined ? null : rounded(pending.activatedAtMs),
    renderedAtMs: rounded(event.atMs),
    routeToReadyMs: pending.routeStartedAtMs === undefined
      ? null
      : elapsed(pending.routeStartedAtMs, event.atMs),
    mountedToReadyMs: elapsed(pending.mountedAtMs, event.atMs),
    idleWaitMs: pending.activatedAtMs === undefined
      ? null
      : elapsed(pending.mountedAtMs, pending.activatedAtMs),
    renderMs: pending.activatedAtMs === undefined
      ? null
      : elapsed(pending.activatedAtMs, event.atMs)
  });
};

const observeWebVitals = () => {
  observeEntries("largest-contentful-paint", (entry) => {
    const largestEntry = entry as LargestContentfulPaintEntry;
    if (largestContentfulPaintMs === null || entry.startTime >= largestContentfulPaintMs) {
      largestContentfulPaintMs = entry.startTime;
      largestContentfulPaintAttribution = describeLargestContentfulPaint(largestEntry);
    }
    publishSnapshot();
  });
  observeEntries("layout-shift", (entry) => recordLayoutShift(entry as LayoutShiftEntry));
  observeEntries(
    "event",
    (entry) => recordInteraction(entry as EventTimingEntry),
    { durationThreshold: 16 }
  );
};

const observeEntries = (
  type: string,
  consume: (entry: PerformanceEntry) => void,
  options: { durationThreshold?: number } = {}
) => {
  if (!("PerformanceObserver" in window)) {
    return;
  }
  const supportedEntryTypes = PerformanceObserver.supportedEntryTypes ?? [];
  if (!supportedEntryTypes.includes(type)) {
    return;
  }
  try {
    const observer = new PerformanceObserver((list) => list.getEntries().forEach(consume));
    observer.observe({
      type,
      buffered: true,
      ...options
    } as PerformanceObserverInit & { durationThreshold?: number });
    observers.push(observer);
  } catch {
    // Diagnostics must remain optional on browsers with partial PerformanceObserver support.
  }
};

const recordLayoutShift = (entry: LayoutShiftEntry) => {
  if (entry.hadRecentInput || !Number.isFinite(entry.value) || entry.value < 0) {
    return;
  }
  const startsNewSession = layoutShiftSessionStartedAtMs === null
    || entry.startTime - lastLayoutShiftAtMs >= 1000
    || entry.startTime - layoutShiftSessionStartedAtMs >= 5000;
  if (startsNewSession) {
    layoutShiftSessionStartedAtMs = entry.startTime;
    layoutShiftSessionValue = entry.value;
  } else {
    layoutShiftSessionValue += entry.value;
  }
  lastLayoutShiftAtMs = entry.startTime;
  cumulativeLayoutShift = Math.max(cumulativeLayoutShift, layoutShiftSessionValue);
  publishSnapshot();
};

const recordInteraction = (entry: EventTimingEntry) => {
  if (!Number.isFinite(entry.duration) || entry.duration < 0 || entry.interactionId <= 0) {
    return;
  }
  interactionDurations.set(
    entry.interactionId,
    Math.max(interactionDurations.get(entry.interactionId) ?? 0, entry.duration)
  );
  publishSnapshot();
};

const createSnapshot = (): FrontendPerformanceDiagnosticSnapshot => {
  const profile = activeProfile;
  if (!profile) {
    throw new Error("Frontend performance diagnostics are not active.");
  }
  const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
  const navigatorWithDiagnostics = navigator as NavigatorWithDiagnostics;
  const resolvedRoute = stableText(routeResolver() ?? currentRoute);
  const lcp = largestContentfulPaintMs === null ? null : rounded(largestContentfulPaintMs);
  const inp = interactionPercentile();
  const cls = Number(cumulativeLayoutShift.toFixed(4));
  const resources = chartResourceSummary();
  const firstRouteStartedAtMs = routeTimings[0]?.startedAtMs ?? null;
  const firstRoutePaintedAtMs = routeTimings[0]?.paintedAtMs ?? performance.now();
  const hasDataReady = routeMilestones.some(
    (milestone) => milestone.route === "overview" && milestone.name === "data-ready"
  );

  return {
    schemaVersion: 1,
    profile,
    capturedAt: new Date().toISOString(),
    ready: routeTimings.some((timing) => timing.route === resolvedRoute)
      && (resolvedRoute !== "overview" || hasDataReady)
      && chartTimings.length > 0
      && pendingCharts.size === 0,
    context: {
      route: resolvedRoute,
      navigationType: navigation?.type ?? "unknown",
      effectiveNetworkType: navigatorWithDiagnostics.connection?.effectiveType ?? "unknown",
      saveData: Boolean(navigatorWithDiagnostics.connection?.saveData),
      viewportWidth: Math.max(0, Math.round(window.innerWidth)),
      viewportHeight: Math.max(0, Math.round(window.innerHeight)),
      devicePixelRatio: nonNegative(window.devicePixelRatio),
      hardwareConcurrency: Math.max(0, Math.round(navigator.hardwareConcurrency || 0)),
      deviceMemoryGiB: navigatorWithDiagnostics.deviceMemory === undefined
        ? undefined
        : nonNegative(navigatorWithDiagnostics.deviceMemory)
    },
    webVitals: {
      lcp: {
        ...rated(lcp, 2500, 4000),
        attribution: largestContentfulPaintAttribution
      },
      inp: rated(inp, 200, 500),
      cls: rated(cls, 0.1, 0.25)
    },
    navigation: navigationTimingSummary(navigation, firstRouteStartedAtMs),
    startupResources: startupResourceSummary(firstRoutePaintedAtMs),
    routes: routeTimings.map((timing) => ({ ...timing })),
    milestones: routeMilestones.map((milestone) => ({ ...milestone })),
    charts: chartTimings.map((timing) => ({ ...timing })),
    pendingCharts: [...pendingCharts.keys()],
    chartResources: resources
  };
};

const navigationTimingSummary = (
  navigation: PerformanceNavigationTiming | undefined,
  appRouteStartAtMs: number | null
): NavigationTimingSummary | null => {
  if (!navigation) {
    return null;
  }
  const secureConnectionStartedAtMs = nonNegative(navigation.secureConnectionStart);
  const responseEndAtMs = rounded(navigation.responseEnd);
  return {
    protocol: navigation.nextHopProtocol || "unknown",
    redirectMs: durationBetween(navigation.redirectStart, navigation.redirectEnd),
    dnsMs: durationBetween(navigation.domainLookupStart, navigation.domainLookupEnd),
    connectMs: durationBetween(navigation.connectStart, navigation.connectEnd),
    tlsMs: secureConnectionStartedAtMs > 0
      ? durationBetween(secureConnectionStartedAtMs, navigation.connectEnd)
      : 0,
    requestToFirstByteMs: durationBetween(navigation.requestStart, navigation.responseStart),
    responseDownloadMs: durationBetween(navigation.responseStart, navigation.responseEnd),
    responseEndAtMs,
    domInteractiveAtMs: rounded(navigation.domInteractive),
    domContentLoadedAtMs: rounded(navigation.domContentLoadedEventEnd),
    loadEventAtMs: rounded(navigation.loadEventEnd),
    appRouteStartAtMs,
    responseEndToRouteStartMs: appRouteStartAtMs === null
      ? null
      : durationBetween(responseEndAtMs, appRouteStartAtMs),
    transferBytes: rounded(navigation.transferSize),
    encodedBodyBytes: rounded(navigation.encodedBodySize),
    decodedBodyBytes: rounded(navigation.decodedBodySize)
  };
};

const startupResourceSummary = (routePaintedAtMs: number): StartupResourceSummary => {
  const resources = performance.getEntriesByType("resource")
    .filter((entry): entry is PerformanceResourceTiming => isStartupResource(entry, routePaintedAtMs))
    .sort((left, right) => left.startTime - right.startTime)
    .slice(0, MAX_RECORDED_ITEMS);
  const networkRequestCount = resources.filter((entry) => entry.transferSize > 0).length;
  const cachedRequestCount = resources.filter(
    (entry) => entry.transferSize === 0 && entry.decodedBodySize > 0
  ).length;
  return {
    cacheState: resolveCacheState(resources.length, networkRequestCount, cachedRequestCount),
    requestCount: resources.length,
    networkRequestCount,
    cachedRequestCount,
    transferBytes: sumResourceField(resources, "transferSize"),
    loadEndAtMs: rounded(Math.max(0, ...resources.map((entry) => entry.responseEnd))),
    resources: resources.map((entry) => ({
      path: resourcePath(entry.name) ?? "unknown",
      initiatorType: entry.initiatorType || "unknown",
      protocol: entry.nextHopProtocol || "unknown",
      cacheState: entry.transferSize > 0 ? "network" : "cache",
      startAtMs: rounded(entry.startTime),
      responseEndAtMs: rounded(entry.responseEnd),
      durationMs: rounded(entry.duration),
      transferBytes: rounded(entry.transferSize),
      encodedBodyBytes: rounded(entry.encodedBodySize),
      decodedBodyBytes: rounded(entry.decodedBodySize)
    }))
  };
};

const isStartupResource = (entry: PerformanceEntry, routePaintedAtMs: number) => {
  if (entry.entryType !== "resource" || entry.startTime > routePaintedAtMs) {
    return false;
  }
  const path = resourcePath(entry.name);
  return path?.startsWith("/assets/") === true && /\.(?:css|js)$/.test(path);
};

const describeLargestContentfulPaint = (
  entry: LargestContentfulPaintEntry
): LargestContentfulPaintAttribution => {
  const element = entry.element;
  return {
    tagName: element?.tagName.toLowerCase() || "unknown",
    id: element?.id ? stableText(element.id) : null,
    classNames: element ? [...element.classList].slice(0, 6).map((name) => stableText(name)) : [],
    resourcePath: resourcePath(entry.url),
    size: rounded(entry.size ?? 0),
    loadTimeMs: rounded(entry.loadTime ?? 0),
    renderTimeMs: rounded(entry.renderTime ?? 0)
  };
};

const resourcePath = (value: string | undefined) => {
  if (!value) {
    return null;
  }
  try {
    const url = new URL(value, window.location.href);
    return url.origin === window.location.origin ? url.pathname : null;
  } catch {
    return null;
  }
};

const chartResourceSummary = (): ChartResourceSummary => {
  const resources = performance.getEntriesByType("resource")
    .filter((entry): entry is PerformanceResourceTiming => isChartResource(entry));
  const networkRequestCount = resources.filter((entry) => entry.transferSize > 0).length;
  const cachedRequestCount = resources.filter(
    (entry) => entry.transferSize === 0 && entry.decodedBodySize > 0
  ).length;

  return {
    cacheState: resolveCacheState(resources.length, networkRequestCount, cachedRequestCount),
    requestCount: resources.length,
    networkRequestCount,
    cachedRequestCount,
    transferBytes: sumResourceField(resources, "transferSize"),
    encodedBodyBytes: sumResourceField(resources, "encodedBodySize"),
    decodedBodyBytes: sumResourceField(resources, "decodedBodySize")
  };
};

const isChartResource = (entry: PerformanceEntry): entry is PerformanceResourceTiming =>
  entry.entryType === "resource"
  && /\/assets\/(?:EChartPanel|echarts|zrender)[^/]*\.js(?:\?|$)/i.test(entry.name);

const sumResourceField = (
  entries: PerformanceResourceTiming[],
  field: "transferSize" | "encodedBodySize" | "decodedBodySize"
) => Math.round(entries.reduce((total, entry) => total + nonNegative(entry[field]), 0));

const resolveCacheState = (
  requestCount: number,
  networkRequestCount: number,
  cachedRequestCount: number
): CacheState => {
  if (requestCount === 0) {
    return "none";
  }
  if (networkRequestCount > 0 && cachedRequestCount === 0) {
    return "network";
  }
  if (cachedRequestCount > 0 && networkRequestCount === 0) {
    return "cache";
  }
  return "mixed";
};

const interactionPercentile = () => {
  if (interactionDurations.size === 0) {
    return null;
  }
  const descending = [...interactionDurations.values()].sort((left, right) => right - left);
  const percentileIndex = Math.min(Math.floor(descending.length / 50), descending.length - 1);
  return rounded(descending[percentileIndex]);
};

const rated = (value: number | null, goodThreshold: number, poorThreshold: number): RatedMetric => {
  if (value === null) {
    return { value: null, rating: "unavailable" };
  }
  if (value <= goodThreshold) {
    return { value, rating: "good" };
  }
  if (value <= poorThreshold) {
    return { value, rating: "needs-improvement" };
  }
  return { value, rating: "poor" };
};

const resetMeasurements = () => {
  routeStarts.clear();
  routeTimings.splice(0, routeTimings.length);
  routeMilestones.splice(0, routeMilestones.length);
  pendingCharts.clear();
  chartTimings.splice(0, chartTimings.length);
  interactionDurations.clear();
  currentRoute = stableText(routeResolver());
  largestContentfulPaintMs = null;
  largestContentfulPaintAttribution = null;
  cumulativeLayoutShift = 0;
  layoutShiftSessionValue = 0;
  layoutShiftSessionStartedAtMs = null;
  lastLayoutShiftAtMs = 0;
  publishSnapshot();
};

const publishSnapshot = () => {
  if (!activeProfile || typeof document === "undefined") {
    return;
  }
  if (!diagnosticOutput) {
    diagnosticOutput = document.createElement("output");
    diagnosticOutput.id = "repoguard-performance-diagnostics";
    diagnosticOutput.hidden = true;
    diagnosticOutput.setAttribute("aria-hidden", "true");
    diagnosticOutput.dataset.testid = "performance-diagnostics";
    (document.body ?? document.documentElement).append(diagnosticOutput);
  }
  diagnosticOutput.textContent = JSON.stringify(createSnapshot());
};

const resolveProfile = (search: string): ControlledPerformanceProfile | undefined => {
  const requested = new URLSearchParams(search).get("performanceProfile")?.trim().toLowerCase();
  return CONTROLLED_PERFORMANCE_PROFILES.find((profile) => profile === requested);
};

const pushBounded = <T>(items: T[], item: T) => {
  if (items.length >= MAX_RECORDED_ITEMS) {
    items.shift();
  }
  items.push(item);
};

const stableText = (value: string | undefined) => value?.trim().slice(0, 80) || "unknown";

const elapsed = (startedAtMs: number, completedAtMs: number) =>
  rounded(Math.max(0, completedAtMs - startedAtMs));

const durationBetween = (startedAtMs: number, completedAtMs: number) =>
  rounded(Math.max(0, nonNegative(completedAtMs) - nonNegative(startedAtMs)));

const rounded = (value: number) => Math.round(nonNegative(value));

const nonNegative = (value: number) => Number.isFinite(value) && value > 0 ? value : 0;
