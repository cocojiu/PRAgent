type ApiRequestResult = "success" | "failed";

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
};

export type FrontendPerformanceReport = {
  route: string;
  apiRequests: FrontendApiRequestObservation[];
  longTasks: FrontendLongTaskObservation[];
};

type BufferHooks = {
  shouldRecord: () => boolean;
  scheduleFlush: () => void;
};

const MAX_API_REQUESTS = 50;
const MAX_LONG_TASKS = 50;
const apiRequests: FrontendApiRequestObservation[] = [];
const longTasks: FrontendLongTaskObservation[] = [];

let hooks: BufferHooks = {
  shouldRecord: () => false,
  scheduleFlush: () => {}
};

export const configureFrontendPerformanceBuffer = (nextHooks: BufferHooks) => {
  hooks = nextHooks;
};

export const observeFrontendApiRequest = (observation: FrontendApiRequestObservation) => {
  if (!hooks.shouldRecord()) {
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
    traceId: optionalStableText(observation.traceId),
    responseBytes: observation.responseBytes === undefined ? undefined : nonNegative(observation.responseBytes),
    startedAtMs: nonNegative(observation.startedAtMs),
    durationMs: nonNegative(observation.durationMs)
  });
  hooks.scheduleFlush();
};

export const observeFrontendLongTask = (observation: FrontendLongTaskObservation) => {
  if (!isRegionRenderObservation(observation) && !hooks.shouldRecord()) {
    return;
  }
  if (longTasks.length >= MAX_LONG_TASKS) {
    return;
  }
  longTasks.push({
    startedAtMs: nonNegative(observation.startedAtMs),
    durationMs: nonNegative(observation.durationMs),
    region: optionalStableText(observation.region),
    operation: optionalStableText(observation.operation),
    itemCount: observation.itemCount === undefined ? undefined : nonNegative(observation.itemCount),
    totalCount: observation.totalCount === undefined ? undefined : nonNegative(observation.totalCount)
  });
  hooks.scheduleFlush();
};

export const hasFrontendPerformanceObservations = () => apiRequests.length > 0 || longTasks.length > 0;

export const drainFrontendPerformanceReport = (route: string | undefined): FrontendPerformanceReport => ({
  route: stableText(route),
  apiRequests: apiRequests.splice(0, apiRequests.length),
  longTasks: longTasks.splice(0, longTasks.length)
});

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
