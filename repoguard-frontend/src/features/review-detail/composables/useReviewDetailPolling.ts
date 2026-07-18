import type { ComputedRef, Ref } from "vue";
import { createPageAwarePoller } from "@/composables/pageAwarePoller";

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
  const poller = createPageAwarePoller({
    intervalMs: () => currentPollIntervalMs.value,
    isEnabled: () => shouldPollTask.value && pollFailureCount.value < maxPollFailures,
    poll: pollReviewStatus
  });

  return {
    cleanupPolling: poller.dispose,
    startPolling: poller.start,
    stopPolling: poller.stop,
    syncPolling: poller.sync
  };
};
