import { watch, type ComputedRef, type Ref } from "vue";
import { createPageAwarePoller } from "@/composables/pageAwarePoller";
import { activeTenant } from "@/stores/tenantContext";
import { readReviewProgressStream } from "../reviewProgressStream";

type UseReviewDetailPollingOptions = {
  currentPollIntervalMs: ComputedRef<number>;
  maxPollFailures: number;
  pollFailureCount: Ref<number>;
  pollReviewStatus: () => void | Promise<void>;
  shouldPollTask: ComputedRef<boolean>;
  getTaskId?: () => number;
};

export const useReviewDetailPolling = ({
  currentPollIntervalMs,
  maxPollFailures,
  pollFailureCount,
  pollReviewStatus,
  shouldPollTask,
  getTaskId
}: UseReviewDetailPollingOptions) => {
  let disposed = false;
  let running = false;
  let stream: AbortController | undefined;
  let healthy = false;
  let failures = 0;
  let retryAt = 0;
  let cursor = "";
  let streamKey = "";
  const enabled = () => shouldPollTask.value && pollFailureCount.value < maxPollFailures;
  const available = () => document.visibilityState !== "hidden" && navigator.onLine !== false;
  const poller = createPageAwarePoller({
    intervalMs: () => currentPollIntervalMs.value,
    isEnabled: () => enabled() && (!healthy || pollFailureCount.value > 0),
    poll: pollReviewStatus
  });

  const disconnect = () => {
    const previous = stream;
    stream = undefined;
    healthy = false;
    previous?.abort();
  };
  const connect = () => {
    if (import.meta.env.VITE_REVIEW_PROGRESS_STREAM !== "true" || !getTaskId || stream
      || !available() || failures >= 3 || Date.now() < retryAt) return;
    const taskId = getTaskId();
    if (!Number.isSafeInteger(taskId) || taskId < 1) return;
    const key = `${activeTenant.value}:${taskId}`;
    if (streamKey !== key) {
      cursor = "";
      streamKey = key;
    }
    const current = new AbortController();
    stream = current;
    const startedAt = Date.now();
    void readReviewProgressStream(taskId, current, cursor, async eventId => {
      if (stream !== current || disposed || !running || !enabled()
        || `${activeTenant.value}:${getTaskId()}` !== key) return;
      healthy = true;
      cursor = eventId;
      poller.stop();
      await pollReviewStatus();
    }).catch(() => undefined).finally(() => {
      if (stream !== current || disposed) return;
      stream = undefined;
      healthy = false;
      // A healthy server rotates streams at 110 seconds. Short failures get bounded retries.
      failures = Date.now() - startedAt >= 100_000 ? 0 : failures + 1;
      retryAt = Date.now() + 30_000;
      if (running && enabled()) poller.start();
    });
  };
  const start = () => {
    if (disposed) return;
    running = true;
    connect();
    poller.sync();
  };
  const stop = () => {
    running = false;
    disconnect();
    poller.stop();
  };
  const sync = () => enabled() ? start() : stop();
  const stopTenantWatch = watch(activeTenant, () => {
    disconnect();
    cursor = "";
    failures = 0;
    retryAt = 0;
    if (running && enabled()) start();
  });
  const availabilityChanged = () => {
    if (!available()) disconnect();
    else if (running) start();
  };
  document.addEventListener("visibilitychange", availabilityChanged);
  window.addEventListener("online", availabilityChanged);
  window.addEventListener("offline", availabilityChanged);
  const dispose = () => {
    disposed = true;
    stop();
    stopTenantWatch();
    poller.dispose();
    document.removeEventListener("visibilitychange", availabilityChanged);
    window.removeEventListener("online", availabilityChanged);
    window.removeEventListener("offline", availabilityChanged);
  };
  return {
    cleanupPolling: dispose,
    startPolling: start,
    stopPolling: stop,
    syncPolling: sync
  };
};
