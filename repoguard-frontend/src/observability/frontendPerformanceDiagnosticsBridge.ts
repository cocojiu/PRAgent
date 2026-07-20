export const CONTROLLED_PERFORMANCE_PROFILES = [
  "desktop",
  "mobile",
  "weak-network",
  "custom"
] as const;

export type ControlledPerformanceProfile = (typeof CONTROLLED_PERFORMANCE_PROFILES)[number];

export const isControlledPerformanceProfile = (
  value: string | null | undefined
): value is ControlledPerformanceProfile =>
  CONTROLLED_PERFORMANCE_PROFILES.some((profile) => profile === value);

export type FrontendPerformanceDiagnosticEvent =
  | { type: "route-started"; route: string; atMs: number }
  | { type: "route-painted"; route: string; atMs: number }
  | { type: "route-milestone"; route: string; name: string; atMs: number }
  | { type: "chart-mounted"; label: string; atMs: number }
  | { type: "chart-activated"; label: string; atMs: number }
  | { type: "chart-rendered"; label: string; atMs: number }
  | { type: "chart-cancelled"; label: string; atMs: number };

type DiagnosticSink = (event: FrontendPerformanceDiagnosticEvent) => void;

const MAX_PENDING_EVENTS = 100;
const pendingEvents: FrontendPerformanceDiagnosticEvent[] = [];
let diagnosticsEnabled = false;
let diagnosticSink: DiagnosticSink | undefined;

export const enableFrontendPerformanceDiagnostics = () => {
  diagnosticsEnabled = true;
};

export const disableFrontendPerformanceDiagnostics = () => {
  diagnosticsEnabled = false;
  diagnosticSink = undefined;
  pendingEvents.splice(0, pendingEvents.length);
};

export const installFrontendPerformanceDiagnosticSink = (sink: DiagnosticSink) => {
  diagnosticSink = sink;
  pendingEvents.splice(0, pendingEvents.length).forEach(sink);
};

export const beginRoutePerformanceTiming = (route: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "route-started", route, atMs: now() });
};

export const completeRoutePerformanceTiming = (route: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  afterNextPaint(() => dispatch({ type: "route-painted", route, atMs: now() }));
};

export const recordRoutePerformanceMilestone = (route: string, name: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "route-milestone", route, name, atMs: now() });
};

export const beginChartPerformanceTiming = (label: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "chart-mounted", label, atMs: now() });
};

export const activateChartPerformanceTiming = (label: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "chart-activated", label, atMs: now() });
};

export const completeChartPerformanceTiming = (label: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "chart-rendered", label, atMs: now() });
};

export const cancelChartPerformanceTiming = (label: string) => {
  if (!diagnosticsEnabled) {
    return;
  }
  dispatch({ type: "chart-cancelled", label, atMs: now() });
};

const dispatch = (event: FrontendPerformanceDiagnosticEvent) => {
  if (!diagnosticsEnabled) {
    return;
  }
  if (diagnosticSink) {
    diagnosticSink(event);
    return;
  }
  if (pendingEvents.length < MAX_PENDING_EVENTS) {
    pendingEvents.push(event);
  }
};

const afterNextPaint = (callback: () => void) => {
  if (typeof window === "undefined" || typeof window.requestAnimationFrame !== "function") {
    callback();
    return;
  }
  window.requestAnimationFrame(() => window.requestAnimationFrame(callback));
};

const now = () => (typeof performance === "undefined" ? Date.now() : performance.now());
