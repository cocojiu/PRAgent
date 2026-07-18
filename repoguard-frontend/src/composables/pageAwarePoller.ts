type PageAwarePollerOptions = {
  intervalMs: () => number;
  isEnabled: () => boolean;
  poll: () => void | Promise<void>;
  isOnline?: () => boolean;
  isPageVisible?: () => boolean;
  jitterRatio?: number;
  random?: () => number;
};

const DEFAULT_JITTER_RATIO = 0.1;

export const createPageAwarePoller = ({
  intervalMs,
  isEnabled,
  poll,
  isOnline = defaultIsOnline,
  isPageVisible = defaultIsPageVisible,
  jitterRatio = DEFAULT_JITTER_RATIO,
  random = Math.random
}: PageAwarePollerOptions) => {
  let disposed = false;
  let inFlight = false;
  let running = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  const clearTimer = () => {
    if (timer === undefined) {
      return;
    }
    clearTimeout(timer);
    timer = undefined;
  };

  const isAvailable = () => isPageVisible() && isOnline();

  const nextDelayMs = () => {
    const baseIntervalMs = Math.max(0, finiteNumberOrZero(intervalMs()));
    const boundedJitterRatio = Math.min(1, Math.max(0, finiteNumberOrZero(jitterRatio)));
    const boundedRandom = Math.min(1, Math.max(0, finiteNumberOrZero(random())));
    const multiplier = 1 - boundedJitterRatio + 2 * boundedJitterRatio * boundedRandom;
    return Math.round(baseIntervalMs * multiplier);
  };

  const canSchedule = () => !disposed && running && !inFlight && isEnabled() && isAvailable();

  const schedule = () => {
    if (timer !== undefined || !canSchedule()) {
      return;
    }
    timer = setTimeout(() => {
      timer = undefined;
      void executePoll();
    }, nextDelayMs());
  };

  const executePoll = async () => {
    if (!canSchedule()) {
      return;
    }
    inFlight = true;
    try {
      await poll();
    } finally {
      inFlight = false;
      schedule();
    }
  };

  const start = () => {
    if (disposed) {
      return;
    }
    running = true;
    schedule();
  };

  const stop = () => {
    running = false;
    clearTimer();
  };

  const sync = () => {
    if (isEnabled()) {
      start();
    } else {
      stop();
    }
  };

  const handleAvailabilityChange = () => {
    if (isAvailable()) {
      schedule();
    } else {
      clearTimer();
    }
  };

  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", handleAvailabilityChange);
  }
  if (typeof window !== "undefined") {
    window.addEventListener("online", handleAvailabilityChange);
    window.addEventListener("offline", handleAvailabilityChange);
  }

  const dispose = () => {
    stop();
    disposed = true;
    if (typeof document !== "undefined") {
      document.removeEventListener("visibilitychange", handleAvailabilityChange);
    }
    if (typeof window !== "undefined") {
      window.removeEventListener("online", handleAvailabilityChange);
      window.removeEventListener("offline", handleAvailabilityChange);
    }
  };

  return {
    dispose,
    start,
    stop,
    sync
  };
};

const defaultIsOnline = () => typeof navigator === "undefined" || navigator.onLine !== false;

const defaultIsPageVisible = () => typeof document === "undefined" || document.visibilityState !== "hidden";

const finiteNumberOrZero = (value: number) => Number.isFinite(value) ? value : 0;
