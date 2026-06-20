import type { ComputedRef, Ref } from "vue";

type UseReviewDetailPollingOptions = {
  currentPollIntervalMs: ComputedRef<number>;
  maxPollFailures: number;
  pollFailureCount: Ref<number>;
  pollReviewStatus: () => void | Promise<void>;
  shouldPollTask: ComputedRef<boolean>;
};

export const useReviewDetailPolling = ({
  currentPollIntervalMs,
  maxPollFailures,
  pollFailureCount,
  pollReviewStatus,
  shouldPollTask
}: UseReviewDetailPollingOptions) => {
  let pollTimer: ReturnType<typeof setTimeout> | undefined;

  const stopPolling = () => {
    if (!pollTimer) {
      return;
    }
    clearTimeout(pollTimer);
    pollTimer = undefined;
  };

  const startPolling = () => {
    if (pollTimer || !shouldPollTask.value) {
      return;
    }
    if (pollFailureCount.value >= maxPollFailures) {
      stopPolling();
      return;
    }
    pollTimer = setTimeout(() => {
      pollTimer = undefined;
      void pollReviewStatus();
    }, currentPollIntervalMs.value);
  };

  const syncPolling = () => {
    if (shouldPollTask.value) {
      startPolling();
    } else {
      stopPolling();
    }
  };

  return {
    cleanupPolling: stopPolling,
    startPolling,
    stopPolling,
    syncPolling
  };
};
