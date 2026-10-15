import { readonly, ref, watch, type ComputedRef, type Ref } from "vue";
import { createPageAwarePoller } from "@/composables/pageAwarePoller";
import { activeTenant } from "@/stores/tenantContext";
import { readReviewProgressStream } from "../reviewProgressStream";

export type ReviewProgressConnectionState = "connecting" | "connected" | "backoff" | "polling" | "paused" | "disposed";

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
  const connectionState = ref<ReviewProgressConnectionState>("paused");
  const transition = (state: ReviewProgressConnectionState) => {
    if (connectionState.value !== "disposed") connectionState.value = state;
  };
  let failures = 0;
  let retryAt = 0;
  let cursor = "";
  let streamKey = "";
  const enabled = () => shouldPollTask.value && pollFailureCount.value < maxPollFailures;
  const available = () => document.visibilityState !== "hidden" && navigator.onLine !== false;
  const poller = createPageAwarePoller({
    intervalMs: () => currentPollIntervalMs.value,
    isEnabled: () => enabled() && (connectionState.value !== "connected" || pollFailureCount.value > 0),
    poll: pollReviewStatus
  });

  const disconnect = () => {
    const previous = stream;
    stream = undefined;
    transition("paused");
    previous?.abort();
  };
  const connect = () => {
    if (!available()) { transition("paused"); return; }
    if (import.meta.env.VITE_REVIEW_PROGRESS_STREAM !== "true" || !getTaskId) { transition("polling"); return; }
    const taskId = getTaskId();
    if (!Number.isSafeInteger(taskId) || taskId < 1) return;
    const key = `${activeTenant.value}:${taskId}`;
    if (streamKey !== key) {
      disconnect();
      cursor = "";
      failures = 0;
      retryAt = 0;
      streamKey = key;
    }
    if (stream) return;
    if (failures >= 3) { transition("polling"); return; }
    if (Date.now() < retryAt) { transition("backoff"); return; }
    transition("connecting");
    const current = new AbortController();
    stream = current;
    const startedAt = Date.now();
    void readReviewProgressStream(taskId, current, cursor, async eventId => {
      if (stream !== current || disposed || !running || !enabled()
        || `${activeTenant.value}:${getTaskId()}` !== key) return;
      transition("connected");
      cursor = eventId;
      poller.stop();
      await pollReviewStatus();
    }).catch(() => undefined).finally(() => {
      if (stream !== current || disposed) return;
      stream = undefined;
      transition("paused");
      // A healthy server rotates streams at 110 seconds. Short failures get bounded retries.
      failures = Date.now() - startedAt >= 100_000 ? 0 : failures + 1;
      retryAt = Date.now() + 30_000;
      transition(failures >= 3 ? "polling" : "backoff");
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
    transition("disposed");
    document.removeEventListener("visibilitychange", availabilityChanged);
    window.removeEventListener("online", availabilityChanged);
    window.removeEventListener("offline", availabilityChanged);
  };
  return {
    connectionState: readonly(connectionState),
    cleanupPolling: dispose,
    startPolling: start,
    stopPolling: stop,
    syncPolling: sync
  };
};
