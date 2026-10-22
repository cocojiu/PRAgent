type ApiRequestResult = "success" | "failed" | "cancelled";

export type FrontendApiRequestObservation = {
  operation: string;
  path?: string;
  method: string;
  status?: number;
  result: ApiRequestResult;
  traceId?: string;
  responseBytes?: number;
  startedAtMs: number;
  durationMs: number;
};

export type FrontendLongTaskObservation = {
  startedAtMs: number;
  durationMs: number;
  region?: string;
  operation?: string;
  itemCount?: number;
  totalCount?: number;
  apiPath?: string;
  apiTraceId?: string;
  apiResponseBytes?: number;
  apiDurationMs?: number;
};

export type FrontendPerformanceReport = {
  route: string;
  apiRequests: FrontendApiRequestObservation[];
  longTasks: FrontendLongTaskObservation[];
};

type BufferHooks = {
  shouldRecord: () => boolean;
  scheduleFlush: () => void;
  route?: () => string | undefined;
  onBoundaryReset?: () => void;
};

export type PerformanceContext = { boundary: number; navigation: number; route: string };
type ScopedObservation<T> = { value: T; context: PerformanceContext };
let boundary = 0;
let navigation = 0;
let dropped = 0;
let sendFailures = 0;

const MAX_API_REQUESTS = 50;
const MAX_LONG_TASKS = 50;
const MAX_API_RENDER_CORRELATION_MS = 10_000;
const apiRequests: ScopedObservation<FrontendApiRequestObservation>[] = [];
const longTasks: ScopedObservation<FrontendLongTaskObservation>[] = [];
const latestApiByOperation = new Map<string, FrontendApiRequestObservation>();

let hooks: BufferHooks = {
  shouldRecord: () => false,
  scheduleFlush: () => {}
};

export const configureFrontendPerformanceBuffer = (nextHooks: BufferHooks) => {
  hooks = nextHooks;
};

export const capturePerformanceContext = (): PerformanceContext => ({
  boundary, navigation, route: stableText(hooks.route?.())
});

export const resetFrontendPerformanceBoundary = () => {
  boundary++;
  apiRequests.length = 0;
  longTasks.length = 0;
  latestApiByOperation.clear();
  hooks.onBoundaryReset?.();
};

export const advanceFrontendPerformanceNavigation = () => {
  navigation++;
  latestApiByOperation.clear();
};

export const getFrontendPerformanceDeliveryStats = () => ({ dropped, sendFailures });
export const recordFrontendPerformanceSendFailure = () => { sendFailures++; };

export const observeFrontendApiRequest = (
  observation: FrontendApiRequestObservation, context = capturePerformanceContext()
) => {
  if (context.boundary !== boundary) { dropped++; return; }
  const normalizedObservation = normalizeApiObservation(observation);
  if (normalizedObservation.result === "success" && context.navigation === navigation
    && context.route === stableText(hooks.route?.())) {
    if (latestApiByOperation.size >= MAX_API_REQUESTS) latestApiByOperation.delete(latestApiByOperation.keys().next().value!);
    latestApiByOperation.set(normalizedObservation.operation, normalizedObservation);
  }
  if (!hooks.shouldRecord()) {
    return;
  }
  if (apiRequests.length >= MAX_API_REQUESTS) {
    dropped++;
    return;
  }
  apiRequests.push({ value: normalizedObservation, context });
  hooks.scheduleFlush();
};

export const observeFrontendLongTask = (
  observation: FrontendLongTaskObservation, context = capturePerformanceContext()
) => {
  if (context.boundary !== boundary || context.navigation !== navigation) { dropped++; return; }
  if (!isRegionRenderObservation(observation) && !hooks.shouldRecord()) {
    return;
  }
  if (longTasks.length >= MAX_LONG_TASKS) {
    dropped++;
    return;
  }
  const startedAtMs = nonNegative(observation.startedAtMs);
  const matchedApi = recentApiForRender(observation.operation, startedAtMs);
  longTasks.push({ context, value: {
    startedAtMs,
    durationMs: nonNegative(observation.durationMs),
    region: optionalStableText(observation.region),
    operation: optionalStableText(observation.operation),
    itemCount: observation.itemCount === undefined ? undefined : nonNegative(observation.itemCount),
    totalCount: observation.totalCount === undefined ? undefined : nonNegative(observation.totalCount),
    apiPath: optionalStableText(observation.apiPath) ?? matchedApi?.path,
    apiTraceId: optionalStableText(observation.apiTraceId) ?? matchedApi?.traceId,
    apiResponseBytes: observation.apiResponseBytes === undefined
      ? matchedApi?.responseBytes
      : nonNegative(observation.apiResponseBytes),
    apiDurationMs: observation.apiDurationMs === undefined
      ? matchedApi?.durationMs
      : nonNegative(observation.apiDurationMs)
  } });
  hooks.scheduleFlush();
};

export const hasFrontendPerformanceObservations = () => apiRequests.length > 0 || longTasks.length > 0;

export const drainFrontendPerformanceReport = (fallbackRoute?: string): FrontendPerformanceReport => {
  const context = apiRequests[0]?.context ?? longTasks[0]?.context;
  const route = context?.route ?? stableText(fallbackRoute);
  const take = <T>(items: ScopedObservation<T>[]) => {
    const selected: T[] = [];
    for (let index = 0; index < items.length;) {
      const item = items[index]!;
      if (item.context.route === route && item.context.navigation === context?.navigation) {
        selected.push(item.value);
        items.splice(index, 1);
      } else index++;
    }
    return selected;
  };
  return { route, apiRequests: take(apiRequests), longTasks: take(longTasks) };
};

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

const optionalStableText = (value: string | undefined) => {
  const normalized = value?.trim();
  return normalized || undefined;
};

const isRegionRenderObservation = (observation: FrontendLongTaskObservation) =>
  Boolean(optionalStableText(observation.region) || optionalStableText(observation.operation));

const normalizeApiObservation = (observation: FrontendApiRequestObservation): FrontendApiRequestObservation => ({
  ...observation,
  operation: stableText(observation.operation),
  path: stableText(observation.path),
  method: stableText(observation.method),
  status: observation.status && observation.status > 0 ? observation.status : undefined,
  traceId: optionalStableText(observation.traceId),
  responseBytes: observation.responseBytes === undefined ? undefined : nonNegative(observation.responseBytes),
  startedAtMs: nonNegative(observation.startedAtMs),
  durationMs: nonNegative(observation.durationMs)
});

const recentApiForRender = (operation: string | undefined, renderStartedAtMs: number) => {
  const api = latestApiByOperation.get(stableText(operation));
  if (api === undefined) {
    return undefined;
  }
  const apiCompletedAtMs = api.startedAtMs + api.durationMs;
  const distanceMs = renderStartedAtMs - apiCompletedAtMs;
  if (distanceMs < 0 || distanceMs > MAX_API_RENDER_CORRELATION_MS) {
    return undefined;
  }
  return api;
};
